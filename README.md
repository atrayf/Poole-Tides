# Poole Tides

Android app showing three tide curves for Poole Quay, in metres above Poole chart datum (1.40 m below ODN), UK local time:

1. **Observed**: Environment Agency tide gauge 44232 "POOLE RNLI" beside Poole Quay. It reads every 15 minutes in m AOD, and the app adds 1.40 m to get chart datum.
2. **Poole Tides model**: harmonic constituents fitted to the gauge record, plus a meteorological surge correction driven by Bournemouth Airport pressure and wind.
3. **UKHO**: high and low waters from the free UKHO ADMIRALTY UK Tidal API (Discovery tier). This is the same UKHO data that Absolute Tides licenses. The app joins the events with a cosine curve.

## Data used for the model

| Input | Source | Period |
|---|---|---|
| Poole Quay water level | EA export of gauge 44232 supplied by Andrew (`hist/`, Unchecked/Good rows only) | Nov 2009 – Mar 2025 |
| Poole Quay water level | EA flood-monitoring daily archive (`environment.data.gov.uk/flood-monitoring/archive`), Open Government Licence | Oct 2025 – now (rolling ~12 months) |
| Pressure (MSLP), wind speed/direction | NOAA GHCN-hourly station UKI0000EGHH (Bournemouth Airport, WMO 03862) | 1953 to now |

**Parkstone Yacht Club wind:** I couldn't find a public archive of their station's readings, so the model uses Bournemouth Airport wind. If PYC (or Poole Harbour Commissioners) can export their logged history, it can replace or supplement the airport wind in `fit_model.py`.

**Gauge QC:** rows flagged Suspect or Missing are dropped, along with readings outside −1.4 to +2.0 m AOD (instrument faults, e.g. −3.4 / +5.9 m in 2013–14) and spikes more than 0.3 m from a 2-hour rolling median. There is a gap from mid-March to late October 2025 between the two sources.

**Accuracy (out of sample).** Harmonics and surge were fitted on 2009–2023 only and scored on Jan 2024 – Nov 2026 (19,063 hours):

| | Hourly RMSE | Daily-max (HW height) RMSE |
|---|---|---|
| Harmonics only | 17.2 cm | 17.7 cm |
| Harmonics + Bournemouth pressure/wind | **11.1 cm** | **11.6 cm** |

The fitted pressure response is −0.97 cm/hPa, close to the theoretical inverse-barometer −1.0 cm/hPa. The gauge's mean level rises about 11 mm/year over the record, and that trend is included in the predictions. See `model/validation_out_of_sample.png`.

## Model (`model/fit_model.py`)

1. QC the 15-minute gauge data: remove spikes more than 0.5 m from a 2-hour rolling median. Then average to hourly.
2. Harmonic analysis with UTide: OLS, nodal corrections, automatic constituent selection by the Rayleigh criterion. Poole's double high waters need the shallow-water constituents (M4, MS4, M6 and others), and UTide includes them.
3. Residual = observed − harmonic. Regress the residual on:
   * pressure anomaly (MSLP − 1013.25 hPa), and
   * quadratic wind stress components (wind toward east × speed, wind toward north × speed),
   
   each as a trailing 6-hour mean at lags 0, 3, 6, 12 and 24 hours.
4. Validate: fit the surge coefficients on the first 75% of the record and test on the last 25%. The results are written to `surge_model.json`.
5. Write a precomputed 10-minute astronomical prediction table for 2026–2028 (`poole_astro_10min.bin`), which the app ships as an asset.

In the app, the surge term uses Open-Meteo's free hourly pressure and wind for Bournemouth Airport: the past 3 days and a 7-day forecast. Outside that window the model curve is astronomical only.

## UKHO key

Sign up free at the ADMIRALTY Developer Portal (admiraltyapi.portal.azure-api.net). Subscribe to "UK Tidal API – Discovery", then paste the primary key into the app's Settings. The key stays on the phone. The Discovery tier gives 7 days of high and low waters (no intermediate heights). The app picks the Poole station whose name contains "Quay", or else the first Poole station.

## Building

```
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease testReleaseUnitTest
```

The release APK is signed with the debug key so it can be sideloaded. Use a proper key before publishing to Play.

To refresh the model, run `python3 model/fit_model.py <data-dir> <out-dir>`. Then copy `poole_astro_10min.bin` and `surge_model.json` into `android/app/src/main/assets/`, and the `test_fixtures/*` files into `android/app/src/test/resources/`.
