"""ADMIRALTY Simplified Harmonic Method (NP201 Part III / NP159) for 36A POOLE HARBOUR.

Follows the NP201 worked form: each of M2, S2, K1, O1 is a vector H*F*cos(A+g - pT/24),
where Table VII's daily A and F fold in the satellite constituents (N2 and L2 into M2,
K2 into S2, P1 into K1, Q1 into O1). A and F are computed here from astronomical
arguments; the satellite ratios were fitted to the 2000 Table VII (rms 1-3 deg).
Shallow water: R sin r + i R cos r = sum over M2, S2;  h4 = R^2 F4 cos(2r + f4),
h6 = R^3 F6 cos(3r + f6).
"""
import numpy as np, pandas as pd, glob
C=dict(Z0=1.58, M2=(286,0.41), S2=(306,0.18), K1=(117,0.09), O1=(349,0.05), f4=221,F4=0.779,f6=3,F6=0.502)
d2r=np.pi/180
def shm(t, c=C, sw=True):
    jd=(t - pd.Timestamp("2000-01-01 12:00",tz="UTC"))/pd.Timedelta("1D"); T=np.asarray(jd)/36525
    s=218.3164+481267.8812*T; h=280.4661+36000.7698*T; p=83.3532+4069.0137*T; N=(125.0445-1934.1363*T)*d2r
    ut=np.asarray(t.hour+t.minute/60+t.second/3600); T0=180+15*ut
    e=lambda a: np.exp(1j*np.asarray(a)*d2r)
    fM2=1.0004-0.0373*np.cos(N)+0.0002*np.cos(2*N); uM2=-2.14*np.sin(N)
    fK1=1.006+0.115*np.cos(N)-0.009*np.cos(2*N); uK1=-8.86*np.sin(N)+0.68*np.sin(2*N)
    fO1=1.009+0.187*np.cos(N)-0.015*np.cos(2*N); uO1=10.80*np.sin(N)-1.34*np.sin(2*N)
    fK2=1.024+0.286*np.cos(N)+0.008*np.cos(2*N); uK2=-17.74*np.sin(N)+0.68*np.sin(2*N)
    VM2=2*T0+2*h-2*s+uM2
    E={"M2":fM2*e(VM2)*(1+0.176*e(19-(s-p))+0.030*e(158+(s-p))),
       "S2":e(2*T0)+0.244*fK2*e(2*T0+2*h+uK2),
       "K1":fK1*e(T0+h-90+uK1)+0.341*e(T0-h+90),
       "O1":fO1*e(T0+h-2*s+90+uO1)*(1+0.176*e(4-(s-p)))}
    # term = H F cos(theta) with theta = A + g - pT/24 = -(arg E) + g ; vector z = H F e^{i(arg E - g)}
    z={k:c[k][1]*E[k]*e(-c[k][0]) for k in E}
    sd=z["M2"]+z["S2"]; R=np.abs(sd); phi=np.angle(sd)
    hh=c["Z0"]+sd.real+z["K1"].real+z["O1"].real
    if sw:
        hh=hh+c["F4"]*R**2*np.cos(2*phi-c["f4"]*d2r)+c["F6"]*R**3*np.cos(3*phi-c["f6"]*d2r)
    return pd.Series(hh,index=t)
if __name__=="__main__":
    parts=[pd.read_csv(f,header=None,names=["t","m","v"])[["t","v"]] for f in glob.glob("../data/ea/*.csv")]
    g=pd.concat(parts); g["t"]=pd.to_datetime(g.t,utc=True,format="ISO8601"); g["v"]=pd.to_numeric(g.v,errors="coerce")
    g=g.dropna().drop_duplicates("t").set_index("t").v.sort_index().resample("15min").mean()+1.32
    g[(g<0.41)|(g>3.4)]=np.nan
    o=g["2026-01-01":].dropna()
    rm=lambda x: float(np.sqrt(np.nanmean(x**2)))
    p=shm(o.index); print("RMSE",round(rm(o-p),3),"bias",round(float((o-p).mean()),3), "no SW", round(rm(o-shm(o.index,sw=False)),3))
    t=pd.date_range("2026-11-01",periods=144*30,freq="10min",tz="UTC")
    def peaks(s, above=1.5):
        v=s.values; i=np.where((v[1:-1]>v[:-2])&(v[1:-1]>=v[2:])&(v[1:-1]>above))[0]+1; return len(i)
    print("peaks/30d",peaks(shm(t)))
    day=pd.date_range("2026-11-13",periods=145,freq="10min",tz="UTC"); sv=shm(day)
    print(" ".join(f"{d.strftime('%H%M')}:{x:.2f}" for d,x in sv.iloc[::6].items()))
