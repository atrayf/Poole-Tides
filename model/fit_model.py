"""Poole Tides model fit.

1. Load Poole Quay (EA station 44232, POOLE RNLI) 15-min water levels (m AOD).
2. Load Bournemouth Airport (EGHH) hourly sea-level pressure and wind.
3. Harmonic analysis (UTide) of the gauge record -> tidal constituents.
4. Regress the non-tidal residual on pressure anomaly and wind stress
   (several lags) -> meteorological surge coefficients.
5. Validate on a held-out block, write constituents, coefficients,
   and a precomputed astronomical prediction table for the app.
"""
import glob, json, sys, os
import numpy as np, pandas as pd, utide

DATA = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
CD_OFFSET = 1.40  # Poole Town Quay chart datum is 1.40 m below ODN (AOD)
LAT = 50.7172

# ---------- gauge ----------
frames = [pd.read_csv(f, header=None, names=["t", "m", "v"]) for f in sorted(glob.glob(f"{DATA}/ea/*.csv"))]
g = pd.concat(frames)
g["t"] = pd.to_datetime(g["t"], utc=True)
g["v"] = pd.to_numeric(g["v"], errors="coerce")
g = g.dropna().drop_duplicates("t").set_index("t")["v"].sort_index()
g = g.resample("15min").mean()
# basic QC: spikes > 0.5 m from 1 h rolling median
med = g.rolling("2h", center=True).median()
g[(g - med).abs() > 0.5] = np.nan
g = g + CD_OFFSET  # metres above chart datum
print("gauge:", g.index.min(), g.index.max(), "valid", g.notna().sum(), "of", len(g))

# ---------- weather ----------
w = pd.read_csv(f"{DATA}/wx/eghh.csv", parse_dates=["time"])
w["time"] = w["time"].dt.tz_localize("UTC")
w = w.set_index("time").sort_index()
w = w[w.index >= g.index.min() - pd.Timedelta("10D")]
w = w[~w.index.duplicated()].resample("1h").mean().interpolate(limit=6)
wd = np.deg2rad(w["wdir"])  # direction wind comes FROM
spd = w["wspd"]
# wind TOWARD east / north components, quadratic stress
u = -spd * np.sin(wd)
v = -spd * np.cos(wd)
met = pd.DataFrame({
    "p": w["slp"] - 1013.25,
    "tu": u * spd,
    "tv": v * spd,
})
print("weather:", met.index.min(), met.index.max())

# ---------- harmonic analysis ----------
hourly = g.resample("1h").mean()
tt = hourly.index
tnum = (tt - pd.Timestamp("1970-01-01", tz="UTC")) / pd.Timedelta("1D")
ok = hourly.notna().values
coef = utide.solve(tnum[ok].values, hourly.values[ok], lat=LAT, method="ols",
                   conf_int="linear", trend=False, nodal=True, verbose=False,
                   constit="auto", epoch="1970-01-01")
print("constituents:", len(coef.name))
order = np.argsort(-coef.A)
for i in order[:15]:
    print(f"  {coef.name[i]:6s} A={coef.A[i]:.3f} g={coef.g[i]:6.1f}")

tide = pd.Series(utide.reconstruct(tnum.values, coef, verbose=False, epoch="1970-01-01").h, index=tt)
resid = hourly - tide
print("harmonic-only RMSE (m):", float(np.sqrt(np.nanmean(resid ** 2))))

# ---------- surge regression ----------
LAGS = [0, 3, 6, 12, 24]
def features(m):
    X = {}
    for lag in LAGS:
        sh = m.shift(lag, freq="1h") if lag else m
        # 6 h running mean smooths station noise
        sm = sh.rolling("6h").mean()
        for c in m.columns:
            X[f"{c}_{lag}"] = sm[c]
    return pd.DataFrame(X)

X = features(met).reindex(tt)
df = X.assign(r=resid).dropna()
split = df.index[int(len(df) * 0.75)]
tr, te = df[df.index < split], df[df.index >= split]
cols = [c for c in df.columns if c != "r"]
def fit(d):
    A = np.column_stack([d[cols].values, np.ones(len(d))])
    beta, *_ = np.linalg.lstsq(A, d["r"].values, rcond=None)
    return beta
def pred(d, beta):
    return np.column_stack([d[cols].values, np.ones(len(d))]) @ beta
b_tr = fit(tr)
rm = lambda x: float(np.sqrt(np.mean(x ** 2)))
val = {
    "train_until": str(split),
    "test_rmse_harmonic_only_m": rm(te["r"].values - np.mean(tr["r"])),
    "test_rmse_harmonic_plus_met_m": rm(te["r"].values - pred(te, b_tr)),
}
beta = fit(df)  # final coefficients use all data
val["all_rmse_harmonic_only_m"] = rm(df["r"].values - df["r"].mean())
val["all_rmse_harmonic_plus_met_m"] = rm(df["r"].values - pred(df, beta))
val["inverse_barometer_cm_per_hPa_sum_of_lags"] = float(sum(beta[[cols.index(f"p_{l}") for l in LAGS]]) * 100)
print(json.dumps(val, indent=1))

# ---------- outputs ----------
json.dump({
    "station": "Poole Quay (EA 44232, POOLE RNLI)",
    "datum": "metres above chart datum (AOD + 1.40)",
    "record": [str(g.index.min()), str(g.index.max())],
    "constituents": [{"name": coef.name[i], "A": float(coef.A[i]), "g": float(coef.g[i])} for i in order],
    "mean": float(coef.mean),
}, open(f"{OUT}/constituents.json", "w"), indent=1)

json.dump({
    "lags_h": LAGS, "smoothing_h": 6,
    "features": cols, "coef": [float(x) for x in beta[:-1]], "intercept": float(beta[-1]),
    "pressure_ref_hPa": 1013.25,
    "notes": "p = MSLP-1013.25 hPa; tu,tv = wind-toward-east/north * speed (m2/s2), Bournemouth Airport",
    "validation": val,
}, open(f"{OUT}/surge_model.json", "w"), indent=1)

# Astronomical prediction table for the app: 10-min steps, centimetres above CD
start = pd.Timestamp("2026-01-01", tz="UTC"); end = pd.Timestamp("2029-01-01", tz="UTC")
pt = pd.date_range(start, end, freq="10min", inclusive="left")
pn = (pt - pd.Timestamp("1970-01-01", tz="UTC")) / pd.Timedelta("1D")
ph = utide.reconstruct(pn.values, coef, verbose=False, epoch="1970-01-01").h
cm = np.round(ph * 100).astype("<i2")
with open(f"{OUT}/poole_astro_10min.bin", "wb") as f:
    f.write(np.array([int(start.timestamp()), 600, len(cm)], dtype="<i8").tobytes())
    f.write(cm.tobytes())
print("table points:", len(cm))

# Validation series for plotting (hourly, last 60 days)
recent = pd.DataFrame({"obs": hourly, "tide": tide})
recent["model"] = recent["tide"] + pd.Series(pred(df, beta), index=df.index).reindex(tt)
recent.tail(24 * 60).to_csv(f"{OUT}/validation_last60d.csv")

# Fixtures for the Android unit test (Kotlin must reproduce these)
FIX = f"{OUT}/test_fixtures"; os.makedirs(FIX, exist_ok=True)
sel = df.index[(df.index >= pt[0])][-300:]
epoch = lambda idx: (idx.view("int64") // 10**9) if hasattr(idx, "view") else idx
wsel = w.loc[sel[0] - pd.Timedelta("40h"): sel[-1]].dropna()
with open(f"{FIX}/weather.csv", "w") as f:
    f.write("t,slp,wspd,wdir\n")
    for ts, r in wsel.iterrows():
        f.write(f"{int(ts.timestamp())},{r.slp},{r.wspd},{r.wdir}\n")
sp = pd.Series(pred(df, beta), index=df.index)
with open(f"{FIX}/expected.csv", "w") as f:
    f.write("t,tide,surge\n")
    for ts in sel:
        f.write(f"{int(ts.timestamp())},{tide[ts]:.4f},{sp[ts]:.5f}\n")
print("fixtures:", len(sel))
