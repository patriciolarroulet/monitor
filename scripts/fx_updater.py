# fx_updater.py
import json, os, time, math
from datetime import datetime, timezone

# Placeholder: reusá tus funciones reales
def get_close_al30():   # pesos
    return float(fetch_last_close("AL30"))      # <-- usar tu función real
def get_close_al30d():  # dólares MEP
    return float(fetch_last_close("AL30D"))     # <-- usar tu función real
def get_close_al30c():  # dólares cable (CCL)
    return float(fetch_last_close("AL30C"))     # <-- usar tu función real
def get_oficial_a3500():
    return float(fetch_bcra_a3500())            # <-- usar tu función real

def safe_div(a, b):
    if b is None or a is None or b == 0 or math.isnan(b) or math.isnan(a):
        return None
    return a / b

def compute_fx():
    al30   = get_close_al30()
    al30d  = get_close_al30d()
    al30c  = get_close_al30c()
    oficial = get_oficial_a3500()

    def _rnd(x, nd=2):
        try:
            return None if x is None else round(x, nd)
        except Exception:
            return None

    # Fórmulas correctas: pesos / dólares
    mep = _rnd(safe_div(al30, al30d), 2)   # MEP = AL30 / AL30D
    ccl = _rnd(safe_div(al30, al30c), 2)   # CCL = AL30 / AL30C

    now_utc = datetime.now(timezone.utc).isoformat()
    return {
        "mep": mep,
        "ccl": ccl,
        "oficial": oficial,
        "ts_utc": now_utc,
        "sources": {
            "mep":  "MEP = close(AL30)/close(AL30D)",
            "ccl":  "CCL = close(AL30)/close(AL30C)",
            "oficial": "BCRA A3500"
        }
    }

    now_utc = datetime.now(timezone.utc).isoformat()
    return {
        "mep": mep,
        "ccl": ccl,
        "oficial": oficial,
        "ts_utc": now_utc,
        "sources": {
            "mep":  "MEP = close(AL30)/close(AL30D)",
            "ccl":  "CCL = close(AL30)/close(AL30C)",
            "oficial": "BCRA A3500"
        }
    }

    now_utc = datetime.now(timezone.utc).isoformat()
    payload = {
        "mep": mep,
        "ccl": ccl,
        "oficial": oficial,
        "ts_utc": now_utc,
        "sources": {
            "mep":  "MEP = close(AL30)/close(AL30D)",
            "ccl":  "CCL = close(AL30)/close(AL30C)",
            "oficial": "BCRA A3500"
        }
    }
    return payload

def write_atomic_json(data, path="output/fx.json"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)  # atómico en Windows/Linux

if __name__ == "__main__":
    fx = compute_fx()
    write_atomic_json(fx, "output/fx.json")

