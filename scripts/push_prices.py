#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
push_prices.py — versión completa (FX + FUTUROS en un solo archivo)

- Descarga múltiples endpoints de Data912 (acciones, opciones, cedears, bonos, etc.)
- Calcula MEP/CCL desde AL30/AL30D/AL30C
- Lee A3500 desde BCRA (API pública v3)
- Genera output/fx.json con esquema:
  {
    "mep":{"value":float,"prev":float|None,"date":None},
    "ccl":{"value":float,"prev":float|None,"date":None},
    "a3500":{"value":float,"prev":float|None,"date":"YYYY-MM-DD"},
    "_timestamp": "...",
    "frozen": false,
    "market_date": "...",
    "last_update": "..."
  }
- Integra scrape de FUTUROS (ROFEX – dalfie.ar) y genera output/futuros.json:
  {
    "asOf": "...",
    "source": "dalfie.ar/ccl (scrape)",
    "spotFrom": "SPOT(calc:MMMYY)",
    "curva": [ ... fila SPOT + contratos ... ],
    "vencimientos": [ ... ]
  }
- Exporta Excel opcional (todo lo descargado/armado)
- Publica al backend /api/ingest/prices (con switch SEND_TO_BACKEND)
"""

import os, time, json, re, typing as t
from datetime import datetime, date, time as dtime
import datetime as dt
from zoneinfo import ZoneInfo

import requests
import pandas as pd
import urllib3
import math
import tempfile
from bs4 import BeautifulSoup
from pandas.tseries.offsets import BMonthEnd
import re
from bs4 import BeautifulSoup
from datetime import datetime, timezone


import warnings
warnings.filterwarnings(
    "ignore",
    message=r"The behavior of DataFrame concatenation with empty or all-NA entries is deprecated"
)


urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# ================== CONFIG ==================
BACKEND_BASE = (
    os.getenv("BACKEND_BASE")
    or os.getenv("API_BASE")            # por si ya lo usás en otro lado
    or os.getenv("MONITOR_API_BASE")    # alias
    or ("http://127.0.0.1:8080" if os.getenv("ENV", "dev").lower() in ("dev","local") 
        else "https://monitor-production-da5e.up.railway.app")
)
BACKEND_BASE = BACKEND_BASE.rstrip("/")

BACKEND_INGEST_URL  = f"{BACKEND_BASE}/api/ingest/prices"
BACKEND_TICKERS_URL = f"{BACKEND_BASE}/api/lecaps/tickers"

print(f"[CFG] BACKEND_BASE={BACKEND_BASE}")

SEND_TO_BACKEND      = True
PERIOD_SECONDS       = 5
GUARDAR_EXCEL        = False

FREEZE_AFTER_1705    = False
MARKET_FREEZE_TIME   = dtime(23, 5, 0)

# --- rutas base ---
BASE_DIR     = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.normpath(os.path.join(BASE_DIR, ".."))
OUTPUT_DIR   = os.path.join(PROJECT_ROOT, "output")
os.makedirs(OUTPUT_DIR, exist_ok=True)

# --- archivos de salida ---
FX_JSON        = os.path.join(OUTPUT_DIR, "fx.json")
SNAPSHOT_JSON  = os.path.join(OUTPUT_DIR, "fx.snapshot.json")
FUTUROS_JSON   = os.path.join(OUTPUT_DIR, "futuros.json")
BCRA_JSON      = os.getenv("BCRA_JSON_PATH", os.path.join(OUTPUT_DIR, "bcra.json"))

# --- series ---
CER_URL   = os.getenv("CER_URL",   "").strip()
TAMAR_URL = os.getenv("TAMAR_URL", "").strip()

DEBUG = os.getenv("MONITOR_DEBUG", "0") == "1"

if DEBUG:
    print(f"[PATHS] PROJECT_ROOT={PROJECT_ROOT}")
    print(f"[PATHS] OUTPUT_DIR  ={OUTPUT_DIR}")
    print(f"[PATHS] BCRA_JSON   ={BCRA_JSON}")
    print(f"[BCRA] CER_URL={CER_URL or '(vacía)'}")
    print(f"[BCRA] TAMAR_URL={TAMAR_URL or '(vacía)'}")


# Ventana de días hacia atrás para poblar el json (buffer)
BCRA_LOOKBACK_DAYS = 120

# --- CAUCIONES ---
CAUCIONES_URL = "https://dalfie.ar/ccl/"
CAUCIONES_JSON = os.path.join(OUTPUT_DIR, "cauciones.json")

# === Backend cauciones ===
BACKEND_CAU_URL = f"{BACKEND_BASE}/api/cauciones/ingest"  # cambia a /api/cauciones si tu endpoint es ese
SEND_CAU_TO_BACKEND = True           # dejar True en prod
WRITE_CAU_JSON_BACKUP = True         # opcional: seguir guardando output/cauciones.json


# Feriados desde XLSX
HOLIDAYS_XLSX   = os.path.join(PROJECT_ROOT, "src", "main", "resources", "data", "API LECAPS_con_comisiones.xlsx")
HOLIDAYS_SHEET  = "FERIADOS"
HOLIDAYS_COLUMN = 0
_HOL_CACHE = {"path": None, "mtime": None, "set": set()}

# --- BCRA SERIES (v3.0/monetarias/{id}) ---
BCRA_VAR_IDS = {
    "CER":    30,
    "A3500":   5,
    "BADLAR":  7,
    "TAMAR":  44,
}
BCRA_SERIES_START = os.getenv("BCRA_SERIES_START", "2024-12-30")

# Data912 endpoints (restauramos todos)
ENDPOINTS = {
    "Acciones_ARG": "live/arg_stocks",
    "Opciones_ARG": "live/arg_options",
    "Cedears_ARG":  "live/arg_cedears",
    "Letras_ARG":   "live/arg_notes",
    "Corp_ARG":     "live/arg_corp",
    "Bonos_ARG":    "live/arg_bonds",     # <- de acá salen AL30, AL30D, AL30C
    "ADRs_USA":     "live/usa_adrs",
}
BASE_URL = "https://data912.com/"

# ----- FUTUROS (ROFEX) CONFIG -----
ROFEX_URL = "https://dalfie.ar/ccl/"
ROFEX_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                  "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    "Accept-Language": "es-AR,es;q=0.9,en;q=0.8",
}
MES = {"ENE":1,"FEB":2,"MAR":3,"ABR":4,"MAY":5,"JUN":6,
       "JUL":7,"AGO":8,"SEP":9,"OCT":10,"NOV":11,"DIC":12}

TZ_AR = ZoneInfo("America/Argentina/Buenos_Aires")

# ================== UTILS ==================
def hhmmss(): return datetime.now(TZ_AR).strftime("%H:%M:%S")
def ddmmyyyy(): return datetime.now(TZ_AR).strftime("%d/%m/%Y")
def now_local(): return datetime.now(TZ_AR)

def pct_change_from(prev, price):
    try:
        if prev in (None, 0.0) or price is None: return 0.0
        return round(((float(price) / float(prev)) - 1.0) * 100.0, 2)
    except Exception: return 0.0

def write_atomic_json(a, b):
    """Escribe JSON UTF-8 (sin BOM) de forma atómica.
    Acepta (path, data) o (data, path) en cualquier orden.
    """
    # detectar orden de argumentos
    if isinstance(a, (dict, list)) and isinstance(b, (str, bytes, os.PathLike)):
        data, path = a, b
    elif isinstance(b, (dict, list)) and isinstance(a, (str, bytes, os.PathLike)):
        path, data = a, b
    else:
        # mejor fallar con mensaje claro
        raise TypeError(f"write_atomic_json espera (path, data) o (data, path), recibió: {type(a)} y {type(b)}")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".tmp_", suffix=".json")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    finally:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass


def clean_nans(obj):
    if isinstance(obj, dict):
        return {k: clean_nans(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [clean_nans(v) for v in obj]
    if isinstance(obj, float):
        if math.isnan(obj) or math.isinf(obj):
            return None
        return obj
    return obj

def read_json_or_none(path):
    try:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception as e:
        print(f"⚠️ No pude leer {path}: {e}")
    return None

def fetch_backend_tickers():
    try:
        r = requests.get(BACKEND_TICKERS_URL, timeout=5); r.raise_for_status()
        return {str(x).strip().upper() for x in (r.json() or [])}
    except Exception as e:
        print("⚠️ No pude leer /api/lecaps/tickers:", e)
        return set()

def is_after_freeze_time():
    return now_local().time() >= MARKET_FREEZE_TIME if FREEZE_AFTER_1705 else False

def load_holidays_xlsx(path=HOLIDAYS_XLSX, sheet=HOLIDAYS_SHEET, col=HOLIDAYS_COLUMN):
    try:
        path = os.path.abspath(path); mtime = os.path.getmtime(path)
    except Exception:
        return _HOL_CACHE.get("set", set())
    if _HOL_CACHE["path"] == path and _HOL_CACHE["mtime"] == mtime:
        return _HOL_CACHE["set"]
    try:
        df = pd.read_excel(path, sheet_name=sheet, usecols=[col], header=None, engine="openpyxl")
        vals = df.iloc[:, 0].dropna().tolist(); out = set()
        for v in vals:
            ts = pd.to_datetime(v, errors="coerce", dayfirst=True)
            if not pd.isna(ts): out.add(ts.date())
        _HOL_CACHE.update({"path": path, "mtime": mtime, "set": out})
        print(f"[FERIADOS] Cargados {len(out)} días desde {os.path.basename(path)}!{sheet}")
        return out
    except Exception as e:
        print(f"⚠️ Error leyendo feriados XLSX: {e}")
        return _HOL_CACHE.get("set", set())

def holidays_set(): return load_holidays_xlsx()
def is_weekend(d): return d.weekday() >= 5
def is_market_holiday(d): return d in holidays_set() or is_weekend(d)
def market_date(today=None):
    today = today or now_local().date()
    d = today
    while is_market_holiday(d):
        d = d - dt.timedelta(days=1)
    return d

# --- BONCER: tickers desde el Excel "API LECAPS_con_comisiones.xlsx" (hoja BONCER) ---
def _normalize_header(s: str) -> str:
    if s is None:
        return ""
    t = str(s).strip().lower()
    t = (t.replace("á","a")
           .replace("é","e")
           .replace("í","i")
           .replace("ó","o")
           .replace("ú","u"))
    t = " ".join(t.split())
    return t

def load_boncer_tickers_from_xlsx(path=HOLIDAYS_XLSX, sheet="BONCER"):
    """
    Lee la columna de tickers de la hoja BONCER.
    Devuelve un set en MAYÚSCULAS, sin espacios ni guiones.
    """
    try:
        df = pd.read_excel(path, sheet_name=sheet, engine="openpyxl")
    except Exception as e:
        print(f"⚠️ No pude leer hoja {sheet} de {path}: {e}")
        return set()

    # Mapeo de headers normalizados
    headers = {_normalize_header(c): c for c in df.columns}
    # posibles nombres de la columna ticker
    for key in ("ticker", "simbolo", "símbolo", "symbol"):
        if key in headers:
            col = headers[key]
            vals = (
                df[col]
                .dropna()
                .astype(str)
                .str.strip()
                .str.upper()
                .str.replace(" ", "", regex=False)
                .str.replace("-", "", regex=False)
            )
            out = {v for v in vals if v}
            print(f"[POST] Tickers BONCER (XLSX): {len(out)} encontrados")
            return out

    print("⚠️ No encontré columna de TICKER en la hoja BONCER del XLSX")
    return set()

# También permitimos sumar manualmente vía ENV
EXTRA_POST_TICKERS_ENV = {
    t.strip().upper().replace(" ", "").replace("-", "")
    for t in os.getenv("EXTRA_POST_TICKERS", "").split(",")
    if t.strip()
}

# Unión final (desde XLSX + ENV)
EXTRA_POST_TICKERS = load_boncer_tickers_from_xlsx() | EXTRA_POST_TICKERS_ENV
print(f"[POST] Tickers extra (BONCER): {sorted(EXTRA_POST_TICKERS)}")

def should_freeze_now():
    """
    Devuelve True si hay que congelar (feriado/fin de semana o después de la hora).
    Para pruebas se puede desactivar con la var de entorno DISABLE_FREEZE=1.
    """
    # Override de prueba vía ENV
    if os.environ.get("DISABLE_FREEZE", "0") == "1":
        print("[FREEZE DEBUG] Override: DISABLE_FREEZE=1 -> NO freeze.")
        return False

    today = now_local().date()
    weekend = is_weekend(today)
    in_holidays = today in holidays_set()
    after_time = is_after_freeze_time()

    will_freeze = weekend or in_holidays or after_time
    print(f"[FREEZE DEBUG] today={today} weekend={weekend} in_holidays={in_holidays} "
          f"after_time={after_time} => FREEZE={will_freeze}")
    return will_freeze

# ================== FETCH ==================
def fetch_market_data():
    """
    Descarga los endpoints de Data912 declarados en ENDPOINTS y arma:
      - dataframes: dict nombre->DataFrame
      - df_completo: DataFrame consolidado con normalización mínima
      - resultados_bcra: dict vacío (A3500 se rehidrata luego desde bcra.json)
    """
    dataframes = {}
    resultados_bcra = {}  # ← importante: siempre devolver este dict (aunque vacío)

    # --- Data912 ---
    for nombre, path in ENDPOINTS.items():
        full_url = BASE_URL + path
        try:
            resp = requests.get(full_url, timeout=10)
            resp.raise_for_status()
            data = resp.json()
            df = pd.DataFrame(data)
            df["Fuente"] = nombre
            dataframes[nombre] = df
            print(f"✅ Descargado: {nombre} ({len(df)} filas)")
        except Exception as e:
            print(f"❌ Error en {nombre}: {e}")

           # --- Consolidado ---
    dfs_validos = [
        df for df in dataframes.values()
        if isinstance(df, pd.DataFrame) and not df.empty
    ]

    # Normalizamos: quitamos columnas que son all-NaN en cada DF
    dfs_norm = []
    for df in dfs_validos:
        # conserva solo columnas que tienen al menos un valor no nulo
        keep = df.columns[~df.isna().all(axis=0)]
        df2 = df[keep]
        dfs_norm.append(df2)

    if dfs_norm:
        df_completo = pd.concat(dfs_norm, ignore_index=True)
    else:
        df_completo = pd.DataFrame()

    # Timestamp local AR
    now_ar = dt.datetime.now(TZ_AR)
    if not df_completo.empty:
        df_completo["Hora_imput"] = now_ar.replace(tzinfo=None)


    # Normalización mínima
    if not df_completo.empty:
        ren = {}
        if "last" in df_completo.columns and "c" not in df_completo.columns:
            ren["last"] = "c"
        if "ticker" in df_completo.columns and "symbol" not in df_completo.columns:
            ren["ticker"] = "symbol"
        df_completo = df_completo.rename(columns=ren)

        for col, default in [("q_op", 0), ("v", 0.0)]:
            if col not in df_completo.columns:
                df_completo[col] = default

        # pct_change si falta
        if "pct_change" not in df_completo.columns:
            if "previous_price" in df_completo.columns:
                df_completo["pct_change"] = [
                    pct_change_from(prev, price)
                    for prev, price in zip(df_completo.get("previous_price"), df_completo.get("c"))
                ]
            else:
                df_completo["pct_change"] = 0.0

        # Hora HH:mm:ss
        if "Hora_imput" in df_completo.columns:
            df_completo["Hora_imput"] = df_completo["Hora_imput"].astype(str).str[11:19]

    # ← NO bajar BCRA acá (evita doble llamada). A3500 se cargará luego desde bcra.json.
    return dataframes, df_completo, resultados_bcra

# ================== FX (MEP/CCL/A3500) ==================
def compute_fx_from_bonds(dataframes):
    """
    Devuelve (mep_now, ccl_now, mep_prev, ccl_prev).
    - mep_now / ccl_now se calculan con precios actuales.
    - mep_prev / ccl_prev se estiman a partir de pct_change en AL30, AL30D y AL30C.
    """
    dfb = dataframes.get("Bonos_ARG", pd.DataFrame())
    if dfb is None or dfb.empty:
        print("⚠️ Bonos vacíos: no puedo calcular MEP/CCL.")
        return None, None, None, None

    # Normalizaciones mínimas
    if "last" in dfb.columns and "c" not in dfb.columns:
        dfb["c"] = dfb["last"]
    if "ticker" in dfb.columns and "symbol" not in dfb.columns:
        dfb["symbol"] = dfb["ticker"]

    dfb["sym"] = (
        dfb["symbol"].astype(str)
          .str.upper().str.replace(" ", "", regex=False)
          .str.replace("-", "", regex=False)
    )

    def get_row(sym):
        r = dfb.loc[dfb["sym"] == sym]
        if r.empty:
            return None
        r = r.iloc[0]
        price = float(r["c"]) if pd.notna(r.get("c")) else None
        pct   = float(r["pct_change"]) if "pct_change" in dfb.columns and pd.notna(r.get("pct_change")) else None
        return {"price": price, "pct": pct}

    def prev_from_pct(price, pct):
        if price is None or pct is None:
            return None
        try:
            return float(price) / (1.0 + float(pct) / 100.0)
        except Exception:
            return None

    # obtengo filas
    al30  = get_row("AL30")
    al30d = get_row("AL30D")
    al30c = get_row("AL30C")

    # precios actuales
    p_al30  = al30["price"]  if al30 else None
    p_al30d = al30d["price"] if al30d else None
    p_al30c = al30c["price"] if al30c else None

    # cálculo actual
    mep_now = round(p_al30 / p_al30d, 2) if (p_al30 and p_al30d) else None
    ccl_now = round(p_al30 / p_al30c, 2) if (p_al30 and p_al30c) else None

    # precios previos estimados desde pct_change
    prev_al30  = prev_from_pct(p_al30,  al30["pct"]  if al30  else None)
    prev_al30d = prev_from_pct(p_al30d, al30d["pct"] if al30d else None)
    prev_al30c = prev_from_pct(p_al30c, al30c["pct"] if al30c else None)

    mep_prev = round(prev_al30 / prev_al30d, 2) if (prev_al30 and prev_al30d) else None
    ccl_prev = round(prev_al30 / prev_al30c, 2) if (prev_al30 and prev_al30c) else None

    print(f"[FX DEBUG] AL30={p_al30} AL30D={p_al30d} AL30C={p_al30c} "
          f"-> MEP={mep_now} (prev={mep_prev}) CCL={ccl_now} (prev={ccl_prev})")

    return mep_now, ccl_now, mep_prev, ccl_prev

def extract_a3500_from_bcra(resultados_bcra):
    """Devuelve (value, prev, date) del A3500 desde resultados_bcra."""
    df = resultados_bcra.get("A3500")
    if not isinstance(df, pd.DataFrame) or df.empty:
        return None, None, None
    df = df.sort_values("fecha").reset_index(drop=True)
    val = float(df.iloc[-1]["valor"])
    date_str = df.iloc[-1]["fecha"].strftime("%Y-%m-%d")
    prev = float(df.iloc[-2]["valor"]) if len(df) >= 2 else None
    return val, prev, date_str

def load_bcra_df_from_json(var: str, path: str = BCRA_JSON):
    try:
        obj = read_json_or_none(path)
        m = (obj or {}).get("series", {}).get(var.upper(), {})
        if not m:
            return None
        df = pd.DataFrame({
            "fecha": pd.to_datetime(list(m.keys()), errors="coerce"),
            "valor": pd.to_numeric(list(m.values()), errors="coerce"),
        }).dropna().sort_values("fecha").reset_index(drop=True)
        return df
    except Exception as e:
        if DEBUG:
            print(f"⚠️ No pude convertir {var} desde {path}: {e}")
        return None

# ================== FILAS → BACKEND ==================
def dataframe_to_rows(df_completo):
    rows = []
    if df_completo is None or df_completo.empty:
        print("⚠️ DF vacío, nada para enviar.")
        return rows

    df = df_completo.copy()

    # columnas mínimas
    if "last" in df.columns and "c" not in df.columns:
        df["c"] = df["last"]
    if "ticker" in df.columns and "symbol" not in df.columns:
        df["symbol"] = df["ticker"]
    for col, default in [("q_op", 0), ("v", 0.0)]:
        if col not in df.columns:
            df[col] = default

    # pct_change si falta
    if "pct_change" not in df.columns:
        if "previous_price" in df.columns:
            df["pct_change"] = [
                pct_change_from(prev, price)
                for prev, price in zip(df.get("previous_price", []), df.get("c", []))
            ]
        else:
            df["pct_change"] = 0.0

    # normalización fuerte del símbolo (para comparar contra las listas)
    if "symbol" in df.columns:
        df["symbol"] = (
            df["symbol"]
            .astype(str)
            .str.upper()
            .str.replace(" ", "", regex=False)
            .str.replace("-", "", regex=False)
        )

    # timestamp local AR
    now_hora, now_fecha = hhmmss(), ddmmyyyy()
    if "Hora_imput" in df.columns:
        df["Hora_imput"] = df["Hora_imput"].astype(str).str[11:19]
    else:
        df["Hora_imput"] = now_hora

    # ===== lista de tickers a publicar: backend ∪ XLSX(BONCER) =====
    backend_tickers = fetch_backend_tickers() or set()
    # normalizamos los del backend para que coincidan con el símbolo normalizado
    backend_tickers = {
        str(t).strip().upper().replace(" ", "").replace("-", "")
        for t in backend_tickers
        if t is not None
    }

    # EXTRA_POST_TICKERS viene del bloque que lee BONCER del XLSX (ya normalizado)
    if backend_tickers:
        backend_tickers |= EXTRA_POST_TICKERS
    else:
        backend_tickers = set(EXTRA_POST_TICKERS)  # si el endpoint falla, mandamos al menos los BONCER

    if not backend_tickers:
        print("⚠️ No hay lista de tickers. Se enviará TODO (sin filtrar).")

    # (Opcional) diagnóstico: qué tickers del XLSX no aparecen en el feed
    try:
        market_syms = set(df["symbol"].dropna().astype(str))
        missing = sorted(EXTRA_POST_TICKERS - market_syms)
        if missing:
            print("[DEBUG BONCER] En XLSX pero no en feed:", ", ".join(missing))
    except Exception:
        pass

    # ===== construir filas =====
    enviados = 0
    for _, r in df.iterrows():
        symbol = str(r.get("symbol", "")).strip().upper().replace(" ", "").replace("-", "")
        price = r.get("c")
        if pd.isna(price) or not symbol:
            continue

        # filtrado final
        if backend_tickers and symbol not in backend_tickers:
            continue

        rows.append({
            "ticker": symbol,
            "price": float(price),
            "pct_change": float(0.0 if pd.isna(r.get("pct_change")) else r.get("pct_change")),
            "q_op": int(0 if pd.isna(r.get("q_op")) else r.get("q_op")),
            "v": float(0 if pd.isna(r.get("v")) else r.get("v")),
            "fuente": "PY",
            "hora_input": str(r.get("Hora_imput", now_hora))[:8],
            "fecha_input": now_fecha,
        })
        enviados += 1

    print(f"✅ Filas preparadas para backend: {enviados}")
    return rows


def post_to_backend(payload):
    r = requests.post(BACKEND_INGEST_URL, json=payload, timeout=10)
    print("POST /api/ingest/prices ->", r.status_code)
    r.raise_for_status()

# ================== CAUCIONES ================== #
def _percent_to_decimal(s):
    if s is None:
        return None
    s = str(s).replace("%", "").strip()
    s = s.replace(".", "").replace(",", ".")
    if not s:
        return None
    try:
        return float(s) / 100.0
    except ValueError:
        return None

def _find_cauciones_table(soup: BeautifulSoup):
    # 1) buscar ancla "Cauciones:"
    for el in soup.find_all(True):
        txt = (el.get_text(strip=True) or "").lower()
        if "cauciones" in txt:
            sib = el.find_next_sibling()
            for _ in range(8):
                if sib and getattr(sib, "name", None) == "table":
                    return sib
                sib = sib.find_next_sibling() if sib else None
            break
    # 2) heurística por headers
    for tbl in soup.find_all("table"):
        head = tbl.find("tr")
        if not head:
            continue
        cells = [(td.get_text(strip=True) or "").lower() for td in head.find_all(["th","td"])]
        if any("plazo" in c for c in cells) and any("tna" in c for c in cells):
            return tbl
    return None

def fetch_cauciones(url: str = CAUCIONES_URL) -> list[dict]:
    r = requests.get(url, headers={"User-Agent": "Mozilla/5.0 (MonitorLecaps/1.0)"}, timeout=20)
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "lxml")
    tbl = _find_cauciones_table(soup)
    if tbl is None:
        raise RuntimeError("No encontré la tabla de Cauciones en la página.")

    out = []
    for tr in tbl.find_all("tr"):
        tds = tr.find_all(["th","td"])
        if len(tds) < 2:
            continue
        c0 = (tds[0].get_text(strip=True) or "").lower()
        c1 = (tds[1].get_text(strip=True) or "")
        # saltar encabezados
        if "plazo" in c0 or "tna" in c0 or "tna" in c1.lower():
            continue

        dias_txt = tds[0].get_text(strip=True)
        tna_txt  = tds[1].get_text(strip=True)
        m = re.search(r"\d+", dias_txt)
        if not m:
            continue
        dias = int(m.group(0))
        tna_dec = _percent_to_decimal(tna_txt)
        if tna_dec is None:
            continue
        out.append({"plazoDias": dias, "tna": tna_dec})

    out.sort(key=lambda x: x["plazoDias"])
    return out

def write_cauciones_json(quotes: list[dict], path: str = CAUCIONES_JSON):
    payload = {
        "asOf": datetime.now(timezone.utc).isoformat(),
        "fuente": "dalfie.ar/ccl (scrape)",
        "mercado": "BYMA",
        "moneda": "ARS",
        "quotes": quotes,
    }
    write_atomic_json(clean_nans(payload), path)
    print(f"💾 cauciones.json actualizado ({len(quotes)} plazos).")

def post_cauciones_to_api(quotes: list[dict]):
    """
    Publica cauciones al backend. 'quotes' viene de fetch_cauciones():
    [{"plazoDias": int, "tna": frac(0..1)}, ...]
    Mandamos el mismo modelo (plazoDias + tna en fracción) dentro de 'quotes'.
    """
    payload = {
        "asOf": datetime.now(timezone.utc).isoformat(),
        "fuente": "dalfie.ar/ccl (scrape)",
        "mercado": "BYMA",
        "moneda": "ARS",
        "quotes": quotes,  # <-- fracción (0..1), como venías manejando internamente
    }
    r = requests.post(BACKEND_CAU_URL, json=payload, timeout=20)
    print("POST /api/cauciones/ingest ->", r.status_code)
    r.raise_for_status()


# ================== FUTUROS (ROFEX) ==================
def _txt(el) -> str:
    return (el.get_text(separator=" ", strip=True) if el else "").replace("\xa0", " ").strip()

def _float_ar(s: t.Any) -> t.Optional[float]:
    if s is None: return None
    s = str(s).strip()
    if not s or s == "-": return None
    s = s.replace(".", "").replace(",", ".")
    try: return float(s)
    except ValueError: return None

def _maturity_from_mes(mes_str: str) -> pd.Timestamp:
    """Último día hábil del mes del contrato (DLR/MMMYY)."""
    mmm, yy = mes_str[:3].upper(), int(mes_str[-2:])
    return (pd.Timestamp(2000 + yy, MES[mmm], 1) + BMonthEnd(1)).normalize()

def _find_rofex_table(soup: BeautifulSoup):
    """Devuelve el <table> cuyo header contiene Mes | ROFEX | TNA | TIR | Pase."""
    want = {"mes", "rofex", "tna", "tir", "pase"}
    for tbl in soup.find_all("table"):
        rows = tbl.find_all("tr")
        if not rows:
            continue
        header_cells = [_txt(td).lower() for td in rows[0].find_all("td")]
        if want.issubset(set(header_cells)):
            pos = {name: header_cells.index(name) for name in want}
            return tbl, pos
    return None, None

def _parse_rofex_rows(tbl, pos_map) -> pd.DataFrame:
    rows = []
    for tr in tbl.find_all("tr")[1:]:
        tds = tr.find_all("td")
        if len(tds) < 5:
            continue
        vals = [_txt(td) for td in tds]
        try:
            mes  = vals[pos_map["mes"]]
            rofx = _float_ar(vals[pos_map["rofex"]])
            tna  = _float_ar(vals[pos_map["tna"]])   # en %
            tir  = _float_ar(vals[pos_map["tir"]])   # en %
            pase = _float_ar(vals[pos_map["pase"]])  # en %
        except Exception:
            continue

        if not re.match(r"^[A-ZÁÉÍÓÚÑ]{3}\d{2}$", mes):
            continue

        rows.append({"mes": mes, "rofex": rofx, "tna": tna, "tir": tir, "pase": pase})
    return pd.DataFrame(rows)

def fetch_futuros_consolidados(url: str = ROFEX_URL) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    r = requests.get(url, headers=ROFEX_HEADERS, timeout=20)
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "lxml")

    tbl, pos = _find_rofex_table(soup)
    if tbl is None:
        raise RuntimeError("No encontré la tabla ROFEX (Mes | ROFEX | TNA | TIR | Pase).")

    df = _parse_rofex_rows(tbl, pos)
    if df.empty:
        raise RuntimeError("La tabla ROFEX se encontró pero no se pudieron parsear filas.")

    today = pd.Timestamp.today().normalize()
    df["vencimiento"] = df["mes"].map(_maturity_from_mes)
    df["days_to_mat"] = (df["vencimiento"] - today).dt.days
    # asegurar numérico
    df["tir"] = pd.to_numeric(df["tir"], errors="coerce")
    df["tir_frac"] = df["tir"] / 100.0

    # S = F / (1 + TIR)^(days/360)
    df["spot_est"] = df.apply(
        lambda r: (
            r["rofex"] / ((1.0 + r["tir_frac"]) ** (max(r["days_to_mat"], 0) / 360.0))
            if pd.notna(r["rofex"]) and pd.notna(r["tir_frac"]) else None
        ),
        axis=1
    )

    # front-month con spot estimado
    fm = df.loc[df["spot_est"].notna()].sort_values("days_to_mat").head(1)
    spot_val  = float(fm["spot_est"].iloc[0]) if not fm.empty else None
    spot_from = fm["mes"].iloc[0] if not fm.empty else "N/A"

    # -------- CURVA (SPOT(calc) + contratos) --------
    spot_row = {
        "mes": f"SPOT(calc:{spot_from})",
        "rofex": float(spot_val) if spot_val is not None else None,
        "tna": None,
        "tir": None,
        "pase": None,
        "vencimiento": None,       # sin NaT
        "days_to_mat": None,
        "spot_est": float(spot_val) if spot_val is not None else None,
    }
    cols = ["mes","rofex","tna","tir","pase","vencimiento","days_to_mat","spot_est"]

    # Aseguramos que el DF original tenga todas las columnas
    for c in cols:
        if c not in df.columns:
            df[c] = None

    # Tipamos la fila SPOT con los mismos dtypes que df[cols] (evita el FutureWarning)
    dtypes_map = df[cols].dtypes.to_dict()
    spot_row_df = pd.DataFrame([spot_row], columns=cols).astype(dtypes_map, errors="ignore")

    # Concat SIN warning: primero los contratos y luego SPOT
    tabla_curva = pd.concat([df[cols], spot_row_df], ignore_index=True)

    # Mover la última fila (SPOT) al comienzo
    tabla_curva = pd.concat([tabla_curva.tail(1), tabla_curva.iloc[:-1]], ignore_index=True)

    # -------- VENCIMIENTOS --------
    tabla_vencimientos = (
        df[["mes","vencimiento","days_to_mat","rofex","tna","tir","pase"]]
        .sort_values(["vencimiento","mes"])
        .reset_index(drop=True)
    )

    # Fechas como date amistoso
    for dframe in (tabla_curva, tabla_vencimientos):
        if "vencimiento" in dframe.columns:
            dframe["vencimiento"] = pd.to_datetime(dframe["vencimiento"], errors="coerce").dt.date


    meta = {"spotFrom": spot_from, "spot": spot_val}
    return tabla_curva, tabla_vencimientos, meta

def write_futuros_json(curva_df: pd.DataFrame, venc_df: pd.DataFrame, meta: dict, path: str = FUTUROS_JSON):
    def _jsonable(df: pd.DataFrame):
        out = df.copy()
        if "vencimiento" in out.columns:
            out["vencimiento"] = out["vencimiento"].astype(str).replace("NaT", None)
        out = out.where(pd.notnull(out), None).to_dict(orient="records")
        return out

    payload = {
        "asOf": datetime.now(dt.timezone.utc).isoformat(),
        "source": "dalfie.ar/ccl (scrape)",
        "spotFrom": meta.get("spotFrom"),
        "curva": _jsonable(curva_df),
        "vencimientos": _jsonable(venc_df),
    }

    # ✅ limpieza global de NaN/Inf → None
    payload = clean_nans(payload)

    write_atomic_json(payload, path)
    print(f"💾 futuros.json actualizado ({len(payload['curva'])} curva, {len(payload['vencimientos'])} venc.).")

    
# ================== MAIN LOOP ==================
while True:
    try:
        # 1) Data de mercado (Data912)
        dataframes, df_completo, resultados_bcra = fetch_market_data()

        # 2) Series BCRA (descarga anualizada -> bcra.json) + rehidratación A3500
        try:
            fetch_bcra_series()  # CER, A3500, BADLAR, TAMAR -> output/bcra.json
        except Exception as e:
            if DEBUG:
                print("⚠️ Error en fetch_bcra_series:", e)

        try:
            a3500_df = load_bcra_df_from_json("A3500", BCRA_JSON)
            if a3500_df is not None:
                resultados_bcra["A3500"] = a3500_df
        except Exception as e:
            if DEBUG:
                print("⚠️ Error leyendo A3500 desde bcra.json:", e)

        # 3) Estado de freeze (silencioso salvo DEBUG)
        frozen_now = should_freeze_now()
        if DEBUG:
            print(f"[FREEZE DEBUG] frozen_now={frozen_now}")

        # Snapshot previo para arrastrar datos si está congelado
        prev_fx = read_json_or_none(SNAPSHOT_JSON) or read_json_or_none(FX_JSON) or {}

        # ---------- helper local para variaciones ----------
        def calc_change(cur, prev):
            """
            Devuelve (chg, chg_pct) con redondeo:
              chg = cur - prev
              chg_pct = (cur/prev - 1)*100   (si prev no es 0/None)
            """
            if cur is None or prev is None:
                return None, None
            try:
                chg = round(float(cur) - float(prev), 2)
                chg_pct = None
                if float(prev) != 0.0:
                    chg_pct = round((float(cur) / float(prev) - 1.0) * 100.0, 2)
                return chg, chg_pct
            except Exception:
                return None, None

        # 4) FX (MEP/CCL/A3500) -> fx.json
        try:
            def _carry_prev(key: str):
                """Clona (si existen) value/prev/chg/chg_pct/date del snapshot anterior."""
                obj = prev_fx.get(key, {}) if isinstance(prev_fx, dict) else {}
                return {
                    "value":   obj.get("value"),
                    "prev":    obj.get("prev"),
                    "chg":     obj.get("chg"),
                    "chg_pct": obj.get("chg_pct"),
                    "date":    obj.get("date"),
                }

            if frozen_now:
                fx_out = {
                    "mep":       _carry_prev("mep"),
                    "ccl":       _carry_prev("ccl"),
                    "a3500":     _carry_prev("a3500"),
                    "_timestamp": now_local().isoformat(),
                    "frozen": True,
                    "market_date": market_date().isoformat(),
                    "last_update": now_local().isoformat(),
                }
                write_atomic_json(fx_out, FX_JSON)
                if DEBUG:
                    print("🧊 Freeze activo: fx.json congelado (se conservaron value/prev/chg/chg_pct).")

            else:
                # compute_fx_from_bonds retorna: mep_now, ccl_now, mep_prev, ccl_prev
                mep_now, ccl_now, mep_prev, ccl_prev = compute_fx_from_bonds(dataframes)
                a3500_val, a3500_prev, a3500_date   = extract_a3500_from_bcra(resultados_bcra)

                mep_chg, mep_chg_pct       = calc_change(mep_now, mep_prev)
                ccl_chg, ccl_chg_pct       = calc_change(ccl_now, ccl_prev)
                a3500_chg, a3500_chg_pct   = calc_change(a3500_val, a3500_prev)

                fx_out = {
                    "mep":   {"value": mep_now,   "prev": mep_prev,   "chg": mep_chg,   "chg_pct": mep_chg_pct,   "date": None},
                    "ccl":   {"value": ccl_now,   "prev": ccl_prev,   "chg": ccl_chg,   "chg_pct": ccl_chg_pct,   "date": None},
                    "a3500": {"value": a3500_val, "prev": a3500_prev, "chg": a3500_chg, "chg_pct": a3500_chg_pct, "date": a3500_date},
                    "_timestamp": now_local().isoformat(),
                    "frozen": False,
                    "market_date": market_date().isoformat(),
                    "last_update": now_local().isoformat(),
                }

                write_atomic_json(fx_out, FX_JSON)
                write_atomic_json(fx_out, SNAPSHOT_JSON)
                # log breve (si querés ocultarlo, ponelo bajo DEBUG)
                print(
                    f"💾 fx.json actualizado: "
                    f"mep={mep_now} (prev={mep_prev}, Δ={mep_chg}, Δ%={mep_chg_pct}) "
                    f"ccl={ccl_now} (prev={ccl_prev}, Δ={ccl_chg}, Δ%={ccl_chg_pct}) "
                    f"a3500={a3500_val} (prev={a3500_prev}, Δ={a3500_chg}, Δ%={a3500_chg_pct})"
                )
        except Exception as e:
            print("⚠️ Error calculando/escribiendo FX:", e)

        # 5) FUTUROS (solo si NO está congelado)
        try:
            if frozen_now:
                if DEBUG:
                    print("🧊 Freeze activo: se mantiene futuros.json previo (no se sobreescribe).")
            else:
                curva_df, venc_df, meta = fetch_futuros_consolidados()
                write_futuros_json(curva_df, venc_df, meta, FUTUROS_JSON)
        except Exception as e:
            print(f"⚠️ Error obteniendo/escribiendo FUTUROS: {e}")

        # 6) Backend (ingest precios)
        rows = dataframe_to_rows(df_completo)
        if rows and SEND_TO_BACKEND:
            try:
                post_to_backend(rows)
            except Exception as e:
                print("⚠️ Error publicando al backend:", e)

        # 7) CAUCIONES — scrape -> POST API (y opcionalmente backup local)
        if DEBUG:
            print("===== CAUCIONES: scrape =====")
        try:
            if frozen_now:
                if DEBUG:
                    print("🧊 Freeze activo: no se postea cauciones.")
            else:
                cauc = fetch_cauciones()  # [{'plazoDias': int, 'tna': frac}, ...]
                if SEND_CAU_TO_BACKEND:
                    try:
                        post_cauciones_to_api(cauc)
                    except Exception as e:
                        print(f"⚠️ Error publicando CAUCIONES al backend: {e}")
                if WRITE_CAU_JSON_BACKUP:
                    write_cauciones_json(cauc, CAUCIONES_JSON)
        except Exception as e:
            print(f"⚠️ Error obteniendo/escribiendo CAUCIONES: {e}")

        # 8) Excel opcional
        if GUARDAR_EXCEL:
            try:
                with pd.ExcelWriter(os.path.join(PROJECT_ROOT, "Precios Real Time.xlsx")) as writer:
                    if not df_completo.empty:
                        df_completo.to_excel(writer, sheet_name="Precios", index=False)
                    for nombre, df in dataframes.items():
                        df.to_excel(writer, sheet_name=nombre[:31], index=False)
                    for nombre, df in resultados_bcra.items():
                        df.to_excel(writer, sheet_name=nombre[:31], index=False)
                if DEBUG:
                    print("📁 Excel actualizado.")
            except Exception as e:
                print("⚠️ Error guardando Excel:", e)

    except Exception as e:
        print("Error en loop:", e)

    time.sleep(PERIOD_SECONDS)
