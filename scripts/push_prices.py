# scripts/push_prices.py  — VERSIÓN INTEGRADA (MEP/CCL desde AL30D/AL30C/AL30 + fx.json)
import os
import time
import json
import math
from datetime import datetime, date, time as dtime, timezone
from zoneinfo import ZoneInfo

import requests
import pandas as pd
import datetime as dt
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# ============== CONFIG ==============
BACKEND_INGEST_URL   = "http://127.0.0.1:8080/api/ingest/prices"
BACKEND_TICKERS_URL  = "http://127.0.0.1:8080/api/lecaps/tickers"
PERIOD_SECONDS       = 5
GUARDAR_EXCEL        = True

# Freezer
FREEZE_AFTER_1705    = True
MARKET_FREEZE_TIME   = dtime(22, 5, 0)

# Rutas base (¡definir antes de usarlas!)
BASE_DIR   = os.path.dirname(os.path.abspath(__file__))
OUTPUT_DIR = os.path.normpath(os.path.join(BASE_DIR, "..", "output"))
os.makedirs(OUTPUT_DIR, exist_ok=True)

# Rutas base (ya definidas)
BASE_DIR   = os.path.dirname(os.path.abspath(__file__))
OUTPUT_DIR = os.path.normpath(os.path.join(BASE_DIR, "..", "output"))
os.makedirs(OUTPUT_DIR, exist_ok=True)

# Proyecto (un nivel arriba de /scripts)
PROJECT_ROOT = os.path.normpath(os.path.join(BASE_DIR, ".."))

# Archivos de salida
FX_JSON        = os.path.join(OUTPUT_DIR, "fx.json")
SNAPSHOT_JSON  = os.path.join(OUTPUT_DIR, "fx.snapshot.json")

# --- FERIADOS DESDE XLSX ---
HOLIDAYS_XLSX   = os.path.join(PROJECT_ROOT, "src", "main", "resources", "data", "API LECAPS_con_comisiones.xlsx")
HOLIDAYS_SHEET  = "FERIADOS"
HOLIDAYS_COLUMN = 0  # Columna A

_HOL_CACHE = {"path": None, "mtime": None, "set": set()}

print(f"[FERIADOS] XLSX: {HOLIDAYS_XLSX} | exists={os.path.exists(HOLIDAYS_XLSX)}")

# Si querés mapear símbolo_de_fuente -> ticker_del_Excel, usá este dict.
MAPEO_LECAPS = {
    # "SIMBOLO_FUENTE": "S29G5",
    # "SIMBOLO_FUENTE_2": "S12S5",
}

# ============== FUENTES (Data912) ==============
ENDPOINTS = {
    # OJO: no usamos “live/mep” ni “live/ccl”; los calculamos nosotros con bonos
    "Acciones_ARG": "live/arg_stocks",
    "Opciones_ARG": "live/arg_options",
    "Cedears_ARG": "live/arg_cedears",
    "Letras_ARG": "live/arg_notes",
    "Corp_ARG": "live/arg_corp",
    "Bonos_ARG": "live/arg_bonds",   # <-- de acá salen AL30, AL30D, AL30C
    "ADRs_USA": "live/usa_adrs",
}
BASE_URL = "https://data912.com/"

# ============== UTIL ==============
TZ_AR = ZoneInfo("America/Argentina/Buenos_Aires")

def hhmmss():
    return datetime.now(TZ_AR).strftime("%H:%M:%S")

def ddmmyyyy():
    return datetime.now(TZ_AR).strftime("%d/%m/%Y")

def now_local():
    return datetime.now(TZ_AR)

def pct_change_from(prev, price):
    try:
        prev  = None if pd.isna(prev)  else float(prev)
        price = None if pd.isna(price) else float(price)
        if prev in (None, 0.0) or price is None:
            return 0.0
        return round(((price / prev) - 1.0) * 100.0, 2)
    except Exception:
        return 0.0

def write_atomic_json(data, path):
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)

def read_json_or_none(path):
    try:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception as e:
        print(f"⚠️ No pude leer {path}: {e}")
    return None

def fetch_backend_tickers():
    """Devuelve un set de tickers vigentes desde /api/lecaps/tickers.
       Si falla, devuelve set() y el script no filtra por backend."""
    try:
        r = requests.get(BACKEND_TICKERS_URL, timeout=5)
        r.raise_for_status()
        lst = r.json()
        return {str(x).strip().upper() for x in (lst or [])}
    except Exception as e:
        print("⚠️ No pude leer /api/lecaps/tickers:", e)
        return set()

def is_after_freeze_time():
    if not FREEZE_AFTER_1705:
        return False
    return now_local().time() >= MARKET_FREEZE_TIME

def _normalize_to_date(x):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return None
    ts = pd.to_datetime(x, errors="coerce", dayfirst=False)  # ISO / serial Excel
    if pd.isna(ts):
        ts = pd.to_datetime(x, errors="coerce", dayfirst=True)  # DD/MM/YYYY
    if pd.isna(ts):
        return None
    return ts.date()

def load_holidays_xlsx(path=HOLIDAYS_XLSX, sheet=HOLIDAYS_SHEET, col=HOLIDAYS_COLUMN):
    """Lee FERIADOS!A:A → set(date). Relee solo si cambió el mtime."""
    try:
        path = os.path.abspath(path)
        mtime = os.path.getmtime(path)
    except Exception as e:
        print(f"⚠️ No encuentro feriados XLSX: {path} ({e})")
        return _HOL_CACHE.get("set", set())

    if _HOL_CACHE["path"] == path and _HOL_CACHE["mtime"] == mtime:
        return _HOL_CACHE["set"]

    try:
        df = pd.read_excel(path, sheet_name=sheet, usecols=[col], header=None, engine="openpyxl")
        vals = df.iloc[:, 0].dropna().tolist()
        out = set()
        for v in vals:
            d = _normalize_to_date(v)
            if d:
                out.add(d)
        _HOL_CACHE.update({"path": path, "mtime": mtime, "set": out})
        print(f"[FERIADOS] Cargados {len(out)} días desde {os.path.basename(path)}!{sheet} col A")
        return out
    except Exception as e:
        print(f"⚠️ Error leyendo feriados XLSX: {e}")
        return _HOL_CACHE.get("set", set())

def holidays_set():
    # llamar en cada loop; solo recarga si cambió el archivo
    return load_holidays_xlsx()

def is_weekend(d: dt.date):
    return d.weekday() >= 5  # 5=sábado, 6=domingo

def is_market_holiday(d: dt.date):
    hs = holidays_set()
    return d in hs or is_weekend(d)

def market_date(today=None):
    today = today or now_local().date()
    d = today
    while is_market_holiday(d):
        d = d - dt.timedelta(days=1)
        # si querés “próximo hábil” en vez de “último hábil”, cambiamos la lógica
    return d

def should_freeze_now():
    today = now_local().date()
    return is_market_holiday(today) or is_after_freeze_time()

# ============== DESCARGA Y PREPARACIÓN DF ==============
def fetch_market_data():
    """
    Descarga de endpoints y arma (dataframes, df_completo, resultados_bcra)
    """
    dataframes = {}

    # 1) Data912
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

    # 2) Unificar TODO (dejamos fuera solo endpoints inexistentes)
    df_completo = pd.concat(
        [df for df in dataframes.values() if isinstance(df, pd.DataFrame)],
        ignore_index=True
    ) if any(isinstance(df, pd.DataFrame) for df in dataframes.values()) else pd.DataFrame()

    # 3) Timestamp local AR
    now_ar = dt.datetime.now(TZ_AR)
    if not df_completo.empty:
        df_completo["Hora_imput"] = now_ar.replace(tzinfo=None)

    # 4) Normalización de columnas clave
    if not df_completo.empty:
        rename_map = {}
        if "last" in df_completo.columns and "c" not in df_completo.columns:
            rename_map["last"] = "c"
        if "ticker" in df_completo.columns and "symbol" not in df_completo.columns:
            rename_map["ticker"] = "symbol"
        df_completo = df_completo.rename(columns=rename_map)

        for col, default in [("q_op", 0), ("v", 0.0), ("pct_change", None)]:
            if col not in df_completo.columns:
                df_completo[col] = default

        # Calcular pct_change si vino previous_price y no vino pct_change
        if "pct_change" in df_completo.columns and df_completo["pct_change"].isna().all():
            if "previous_price" in df_completo.columns:
                df_completo["pct_change"] = [
                    pct_change_from(prev, price)
                    for prev, price in zip(df_completo["previous_price"], df_completo["c"])
                ]
            else:
                df_completo["pct_change"] = 0.0

        # Formatear hora a HH:mm:ss (string)
        if "Hora_imput" in df_completo.columns:
            df_completo["Hora_imput"] = df_completo["Hora_imput"].astype(str).str[11:19]

    # 5) BCRA (para A3500/oficial)
    try:
        resultados_bcra = {}
        desde = date(2024, 12, 30)
        hasta = date.today()
        variables = {"CER": 30, "A3500": 5, "BADLAR": 7, "TAMAR": 44}

        for nombre, idvar in variables.items():
            print(f"🔍 BCRA {nombre}")
            dfs = []
            for anio in range(desde.year, hasta.year + 1):
                f_desde = date(anio, 1, 1) if anio != desde.year else desde
                f_hasta = hasta if anio == hasta.year else date(anio, 12, 31)
                url = f"https://api.bcra.gob.ar/estadisticas/v3.0/monetarias/{idvar}?desde={f_desde}&hasta={f_hasta}"
                try:
                    r = requests.get(url, verify=False, timeout=10)
                    r.raise_for_status()
                    data = r.json()["results"]
                    df_tmp = pd.DataFrame(data).drop(columns="idVariable")
                    dfs.append(df_tmp)
                except Exception as e:
                    print(f"  ❌ Error {nombre} {anio}: {e}")

            if dfs:
                dfb = pd.concat(dfs, ignore_index=True)
                dfb["fecha"] = pd.to_datetime(dfb["fecha"])
                dfb["valor"] = pd.to_numeric(dfb["valor"])
                dfb = dfb.sort_values("fecha").reset_index(drop=True)
                # dedup por última variación real
                if not dfb.empty:
                    idx_ult = dfb[dfb["valor"] != dfb["valor"].shift(-1)].index[-1]
                    dfb = dfb.loc[:idx_ult]
                resultados_bcra[nombre] = dfb
                print(f"✅ BCRA {nombre} ok ({len(dfb)} registros)")
        return dataframes, df_completo, resultados_bcra
    except Exception as e:
        print("⚠️ BCRA falló:", e)
        return dataframes, df_completo, {}

# ============== FX: MEP/CCL ==============
def _safe_div(a, b):
    try:
        if a is None or b is None:
            return None
        a = float(a); b = float(b)
        if b == 0.0 or math.isnan(a) or math.isnan(b):
            return None
        return a / b
    except Exception:
        return None

def compute_fx_from_bonds(dataframes, resultados_bcra):
    """
    MEP = AL30 (PESOS) / AL30D (USD)
    CCL = AL30 (PESOS) / AL30C (USD)
    Oficial = último A3500 (BCRA)
    """
    import pandas as pd
    from datetime import datetime, timezone

    def _safe_div(a, b):
        try:
            if a is None or b is None:
                return None
            a = float(a); b = float(b)
            if b == 0.0 or math.isnan(a) or math.isnan(b):
                return None
            return a / b
        except Exception:
            return None

    def _rnd(x, nd=2):
        try:
            return None if x is None else round(x, nd)
        except Exception:
            return None

    df_bonos = dataframes.get("Bonos_ARG", pd.DataFrame())
    if df_bonos is None or df_bonos.empty:
        print("⚠️ Bonos vacíos: no puedo calcular MEP/CCL.")
        return None

    dfb = df_bonos.copy()

    # Normalizar columnas
    if "last" in dfb.columns and "c" not in dfb.columns:
        dfb["c"] = dfb["last"]
    if "ticker" in dfb.columns and "symbol" not in dfb.columns:
        dfb["symbol"] = dfb["ticker"]

    # Normalizar símbolos (sin espacios/guiones y upper)
    dfb["symbol_norm"] = (
        dfb["symbol"].astype(str).str.upper()
           .str.replace(" ", "", regex=False)
           .str.replace("-", "", regex=False)
    )

    def get_close(sym):
        try:
            row = dfb.loc[dfb["symbol_norm"] == sym, :]
            if row.empty:
                return None
            return float(row.iloc[0].get("c"))
        except Exception:
            return None

    al30  = get_close("AL30")   # PESOS
    al30d = get_close("AL30D")  # USD (MEP)
    al30c = get_close("AL30C")  # USD (CCL)

    # DEBUG: ver crudos
    print(f"[FX DEBUG] AL30(pesos)={al30}  AL30D(usd)={al30d}  AL30C(usd)={al30c}")

    # Fórmulas CORRECTAS: pesos / dólares
    mep = _rnd(_safe_div(al30, al30d), 2)
    ccl = _rnd(_safe_div(al30, al30c), 2)

    # Oficial A3500
    oficial = None
    try:
        df_a3500 = resultados_bcra.get("A3500")
        if isinstance(df_a3500, pd.DataFrame) and not df_a3500.empty:
            oficial = float(df_a3500.iloc[-1]["valor"])
    except Exception:
        pass

    ts_utc = datetime.now(timezone.utc).isoformat()
    fx_payload = {
        "mep": mep,
        "ccl": ccl,
        "oficial": oficial,
        "ts_utc": ts_utc,
        "sources": {
            "mep":  "MEP = close(AL30)/close(AL30D)",
            "ccl":  "CCL = close(AL30)/close(AL30C)",
            "oficial": "BCRA A3500"
        }
    }
    print(f"[FX DEBUG] MEP={mep}  CCL={ccl}  OFICIAL={oficial}")
    return fx_payload

# ============== TRANSFORMACIÓN A FILAS PARA BACKEND ==============
def dataframe_to_rows(df_completo):
    """
    Devuelve lista de dicts con:
      ticker, price, pct_change, q_op, v, fuente, hora_input, fecha_input

    Estrategia:
      1) Obtener tickers vigentes desde el backend (/api/lecaps/tickers)
      2) Tomar TODAS las filas del DF
      3) Normalizar columnas y quedarnos SOLO con las que 'symbol' ∈ backend_tickers
         (o aplicar MAPEO_LECAPS si lo usás)
    """
    rows = []
    if df_completo is None or df_completo.empty:
        print("⚠️ DF vacío, nada para enviar.")
        return rows

    df = df_completo.copy()

    # Normalización de columnas mínimas
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

    # Hora/Fecha AR
    now_hora  = hhmmss()
    now_fecha = ddmmyyyy()
    if "Hora_imput" in df.columns:
        df["Hora_imput"] = df["Hora_imput"].astype(str).str[11:19]
    else:
        df["Hora_imput"] = now_hora

    # Tickers vigentes del backend (Excel)
    backend_tickers = fetch_backend_tickers()
    if not backend_tickers:
        print("⚠️ No obtuve tickers del backend; intentaré sin filtrar por lista.")
    else:
        print(f"ℹ️ Tickers esperados (Excel): {len(backend_tickers)}")

    # Construcción de filas
    enviados = 0
    descartados_por_simbolo = []
    for _, r in df.iterrows():
        src_symbol = str(r.get("symbol", "")).strip().upper()
        price = r.get("c")
        if pd.isna(price) or not src_symbol:
            continue

        # Resolver ticker final
        if MAPEO_LECAPS:
            ticker = MAPEO_LECAPS.get(src_symbol)
            if not ticker:
                descartados_por_simbolo.append(src_symbol)
                continue
        else:
            ticker = src_symbol  # asumo que 'symbol' ya es el ticker del Excel
            if backend_tickers and ticker not in backend_tickers:
                descartados_por_simbolo.append(src_symbol)
                continue

        rows.append({
            "ticker": ticker,
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
    if descartados_por_simbolo[:10]:
        unicos = sorted(set(descartados_por_simbolo))
        print(f"ℹ️ Ejemplos descartados por no mapear/estar en Excel (hasta 10): {unicos[:10]}")

    return rows

# ============== PAYLOAD & POST ==============
def build_payload(rows):
    return rows  # ya vienen correctas las claves

def post_to_backend(payload):
    r = requests.post(BACKEND_INGEST_URL, json=payload, timeout=10)
    print("POST /api/ingest/prices ->", r.status_code, r.text)
    r.raise_for_status()

# ============== (OPCIONAL) GUARDAR EXCEL ==============
def guardar_excel(dataframes, df_completo, resultados_bcra, ruta="Precios Real Time.xlsx"):
    try:
        with pd.ExcelWriter(ruta) as writer:
            if df_completo is not None and not df_completo.empty:
                df_completo.to_excel(writer, sheet_name="Precios", index=False)
            for nombre, df in dataframes.items():
                df.to_excel(writer, sheet_name=nombre[:31], index=False)
            for nombre, df in resultados_bcra.items():
                df.to_excel(writer, sheet_name=nombre[:31], index=False)
            if hasattr(writer, "book") and writer.book is not None and writer.book.worksheets:
                writer.book.active = 0
        print(f"📁 Excel guardado: {ruta}")
    except Exception as e:
        print("⚠️ Error guardando Excel:", e)

# ====== LOOP PRINCIPAL (reemplazo completo) ======
while True:
    try:
        dataframes, df_completo, resultados_bcra = fetch_market_data()

        # FREEZE si es feriado/fin de semana o >= 17:05 (usa el XLSX)
        frozen_now = should_freeze_now()

        # último snapshot/valor previo para variaciones y fallback
        prev_fx = read_json_or_none(SNAPSHOT_JSON) or read_json_or_none(FX_JSON) or {}

        # 1) FX: calcular o congelar
        if frozen_now:
            # mantener último valor y actualizar metadatos
            frozen_out = dict(prev_fx or {})
            frozen_out["frozen"] = True
            frozen_out["market_date"] = market_date().isoformat()   # último hábil
            frozen_out["last_update"] = now_local().isoformat()
            write_atomic_json(frozen_out, FX_JSON)
            print(f"🧊 Freeze activo: fx.json congelado | market_date={frozen_out.get('market_date')}")
        else:
            # === REEMPLAZÁ ESTAS 3 LÍNEAS CON TU CÁLCULO REAL ===
            fx_payload = compute_fx_from_bonds(dataframes, resultados_bcra)  # ← tu función real
            # si preferís explícito:
            # mep_price     = ...
            # ccl_price     = ...
            # oficial_price = ...
            # fx_payload = {"mep": mep_price, "ccl": ccl_price, "oficial": oficial_price}
            # =====================================================

            if fx_payload:
                fx_out = dict(fx_payload)
                # variaciones vs. último snapshot
                fx_out["mep_var_pct"] = pct_change_from(prev_fx.get("mep"), fx_out.get("mep"))
                fx_out["ccl_var_pct"] = pct_change_from(prev_fx.get("ccl"), fx_out.get("ccl"))
                fx_out["oficial_var_pct"] = pct_change_from(prev_fx.get("oficial"), fx_out.get("oficial"))

                fx_out["frozen"] = False
                fx_out["market_date"] = market_date().isoformat()    # hoy si hábil
                fx_out["last_update"] = now_local().isoformat()

                write_atomic_json(fx_out, FX_JSON)
                write_atomic_json(fx_out, SNAPSHOT_JSON)
                print(f"💾 fx.json actualizado -> {FX_JSON} | mep={fx_out.get('mep')} ccl={fx_out.get('ccl')} ofi={fx_out.get('oficial')}")
            else:
                print("⚠️ No se pudo calcular FX; se mantiene fx.json previo si existe.")

        # 2) Enviar precios al backend (lecaps, bonos, etc.)
        rows = dataframe_to_rows(df_completo)
        payload = build_payload(rows)
        if payload:
            if frozen_now:
                print("🧊 Freeze activo: NO se publica payload al backend.")
            else:
                post_to_backend(payload)

        # 3) Excel opcional
        if GUARDAR_EXCEL:
            guardar_excel(dataframes, df_completo, resultados_bcra)

    except Exception as e:
        print("Error en loop:", e)

    time.sleep(PERIOD_SECONDS)


