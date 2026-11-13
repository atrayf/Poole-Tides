"""Poole Tides model fit.

Usage: python3 fit_model.py <data-dir> <out-dir>

<data-dir> holds:
  hist/*.csv  long Poole Quay record (EA 44232 export: header block, then
              dateTime,value,quality; 15-min, m AOD, UTC). Optional.
  ea/*.csv    EA flood-monitoring daily archive lines for 44232 (time,measure,value).
  wx/eghh.csv Bournemouth Airport hourly time,slp,wdir,wspd (NOAA GHCNh UKI0000EGHH).

Steps:
1. Merge and QC the gauge record (drop Suspect/Missing, physical bounds, spikes).
2. Out-of-sample check: fit harmonics + surge on data before TEST_FROM,
   score both on data after it.
3. Final fit on everything: UTide harmonics (with linear trend for sea-level
   rise), then regress the residual on Bournemouth pressure anomaly and
   quadratic wind stress (trailing 6 h means at several lags).
4. Write constituents, surge coefficients, a 10-minute astronomical table for
   the app, a validation series and the Android unit-test fixtures.
"""
import glob, json, sys, os
import numpy as np, pandas as pd, utide

DATA, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
CD_OFFSET = 1.32          # gauge AOD -> Poole chart datum; calibrated offset from the Lilliput app (Apr 2026)
LAT = 50.7172
BOUNDS_AOD = (-1.4, 2.0)  # outside this the EA record only has instrument faults
FLOOR_AOD = -0.99         # the gauge bottoms out near -1.00 m AOD; lower lows are clipped, so drop them
TEST_FROM = pd.Timestamp("2024-01-01", tz="UTC")
EPOCH = "1970-01-01"
LAGS, SMOOTH = [0, 3, 6, 12, 24], 6

# ---------- gauge ----------
parts = []
for f in sorted(glob.glob(f"{DATA}/hist/*.csv")):
    h = pd.read_csv(f, skiprows=4, usecols=[0, 1, 2])
    h.columns = ["t", "v", "q"]
    h = h[h["q"].isin(["Unchecked", "Good"])]
    parts.append(h[["t", "v"]])
for f in sorted(glob.glob(f"{DATA}/ea/*.csv")):
    parts.append(pd.read_csv(f, header=None, names=["t", "m", "v"])[["t", "v"]])
g = pd.concat(parts)
g["t"] = pd.to_datetime(g["t"], utc=True, format="ISO8601")
g["v"] = pd.to_numeric(g["v"], errors="coerce")
g = g.dropna().drop_duplicates("t", keep="last").set_index("t")["v"].sort_index()
g = g.resample("15min").mean()
g[(g < BOUNDS_AOD[0]) | (g > BOUNDS_AOD[1])] = np.nan
g[g <= FLOOR_AOD] = np.nan
med = g.rolling("2h", center=True).median()
g[(g - med).abs() > 0.3] = np.nan
g = g + CD_OFFSET
print("gauge:", g.index.min(), g.index.max(), "valid", int(g.notna().sum()), "of", len(g))

# ---------- weather ----------
w = pd.read_csv(f"{DATA}/wx/eghh.csv", parse_dates=["time"])
w["time"] = w["time"].dt.tz_localize("UTC")
w = w.set_index("time").sort_index()
w = w[w.index >= g.index.min() - pd.Timedelta("10D")]
w = w[~w.index.duplicated()].resample("1h").mean().interpolate(limit=6)
wd, spd = np.deg2rad(w["wdir"]), w["wspd"]
u, v = -spd * np.sin(wd), -spd * np.cos(wd)   # wind TOWARD east / north
met = pd.DataFrame({"p": w["slp"] - 1013.25, "tu": u * spd, "tv": v * spd})
print("weather:", met.index.min(), met.index.max())

hourly = g.resample("1h").mean()
tt = hourly.index
days = lambda idx: ((idx - pd.Timestamp("1970-01-01", tz="UTC")) / pd.Timedelta("1D")).values

def harmonic_fit(series):
    # pass the full, evenly spaced series with NaN gaps; confidence intervals are
    # not needed and the Lomb-Scargle path for them runs out of memory on 15 years
    return utide.solve(days(series.index), series.values, lat=LAT, method="ols",
                       conf_int="none", trend=True, nodal=True, verbose=False,
                       constit="auto", epoch=EPOCH)

def harmonic_pred(coef, idx):
    return pd.Series(utide.reconstruct(days(idx), coef, verbose=False, epoch=EPOCH).h, index=idx)

X = {}
for lag in LAGS:
    sm = (met.shift(lag, freq="1h") if lag else met).rolling(f"{SMOOTH}h").mean()
    for c in met.columns:
        X[f"{c}_{lag}"] = sm[c]
X = pd.DataFrame(X).reindex(tt)
cols = list(X.columns)

def surge_fit(r):
    d = X.assign(r=r).dropna()
    A = np.column_stack([d[cols].values, np.ones(len(d))])
    return np.linalg.lstsq(A, d["r"].values, rcond=None)[0]

def surge_pred(beta):
    return pd.Series(X[cols].values @ beta[:-1] + beta[-1], index=tt)

rm = lambda x: float(np.sqrt(np.nanmean(np.asarray(x) ** 2)))

# ---------- out-of-sample validation ----------
train = hourly[tt < TEST_FROM]
coef_tr = harmonic_fit(train)
tide_tr = harmonic_pred(coef_tr, tt)
beta_tr = surge_fit((hourly - tide_tr)[tt < TEST_FROM])
test = (tt >= TEST_FROM) & X.notna().all(axis=1).values & hourly.notna().values
model_tr = tide_tr + surge_pred(beta_tr)
val = {
    "train": f"{hourly.first_valid_index()} to {TEST_FROM}",
    "test": f"{TEST_FROM} to {hourly.last_valid_index()} (weather available)",
    "test_hours": int(test.sum()),
    "test_rmse_harmonic_only_m": rm((hourly - tide_tr)[test]),
    "test_rmse_harmonic_plus_met_m": rm((hourly - model_tr)[test]),
}
# high-water heights in the test period (daily max) - what sailors care about
dmax = lambda s: s[test].resample("1D").max()
val["test_rmse_daily_max_harmonic_only_m"] = rm(dmax(hourly) - dmax(tide_tr))
val["test_rmse_daily_max_harmonic_plus_met_m"] = rm(dmax(hourly) - dmax(model_tr))

# ---------- final fit on everything ----------
coef = harmonic_fit(hourly)
order = np.argsort(-coef.A)
print("constituents:", len(coef.name))
for i in order[:15]:
    print(f"  {coef.name[i]:6s} A={coef.A[i]:.3f} g={coef.g[i]:6.1f}")
tide = harmonic_pred(coef, tt)
resid = hourly - tide
beta = surge_fit(resid)
model = tide + surge_pred(beta)
both = X.notna().all(axis=1).values & hourly.notna().values
val["all_rmse_harmonic_only_m"] = rm(resid[both])
val["all_rmse_harmonic_plus_met_m"] = rm((hourly - model)[both])
val["inverse_barometer_cm_per_hPa_sum_of_lags"] = float(sum(beta[cols.index(f"p_{l}")] for l in LAGS) * 100)
val["trend_mm_per_year"] = float(coef.slope * 365.25 * 1000)
print(json.dumps(val, indent=1))

json.dump({
    "station": "Poole Quay (EA 44232, POOLE RNLI)",
    "datum": f"metres above chart datum (AOD + {CD_OFFSET:.2f})",
    "record": [str(hourly.first_valid_index()), str(hourly.last_valid_index())],
    "mean": float(coef.mean), "trend_m_per_day": float(coef.slope),
    "constituents": [{"name": coef.name[i], "A": float(coef.A[i]), "g": float(coef.g[i])} for i in order],
}, open(f"{OUT}/constituents.json", "w"), indent=1)

json.dump({
    "lags_h": LAGS, "smoothing_h": SMOOTH,
    "features": cols, "coef": [float(x) for x in beta[:-1]], "intercept": float(beta[-1]),
    "pressure_ref_hPa": 1013.25,
    "notes": "p = MSLP-1013.25 hPa; tu,tv = wind-toward-east/north * speed (m2/s2), Bournemouth Airport",
    "validation": val,
}, open(f"{OUT}/surge_model.json", "w"), indent=1)

# Astronomical prediction table for the app: 10-min steps, centimetres above CD
start, end = pd.Timestamp("2026-01-01", tz="UTC"), pd.Timestamp("2029-01-01", tz="UTC")
pt = pd.date_range(start, end, freq="10min", inclusive="left")
cm = np.round(harmonic_pred(coef, pt).values * 100).astype("<i2")
with open(f"{OUT}/poole_astro_10min.bin", "wb") as f:
    f.write(np.array([int(start.timestamp()), 600, len(cm)], dtype="<i8").tobytes())
    f.write(cm.tobytes())
print("table points:", len(cm))

pd.DataFrame({"obs": hourly, "tide": tide, "model": model}).tail(24 * 60).to_csv(f"{OUT}/validation_last60d.csv")
pd.DataFrame({"obs": hourly, "tide": tide_tr, "model": model_tr})[tt >= TEST_FROM].to_csv(f"{OUT}/validation_test_period.csv")

# Fixtures for the Android unit test (Kotlin must reproduce these)
FIX = f"{OUT}/test_fixtures"; os.makedirs(FIX, exist_ok=True)
sel = tt[both & (tt >= start)][-300:]
wsel = w.loc[sel[0] - pd.Timedelta("40h"): sel[-1]].dropna()
with open(f"{FIX}/weather.csv", "w") as f:
    f.write("t,slp,wspd,wdir\n")
    for ts, r in wsel.iterrows():
        f.write(f"{int(ts.timestamp())},{r.slp},{r.wspd},{r.wdir}\n")
sp = surge_pred(beta)
with open(f"{FIX}/expected.csv", "w") as f:
    f.write("t,tide,surge\n")
    for ts in sel:
        f.write(f"{int(ts.timestamp())},{tide[ts]:.4f},{sp[ts]:.5f}\n")
print("fixtures:", len(sel))
