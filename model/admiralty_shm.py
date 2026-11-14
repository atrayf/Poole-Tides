"""ADMIRALTY Simplified Harmonic Method (NP159) for 36A POOLE HARBOUR, checked against the gauge.
Run from a folder whose ../data/ea holds EA archive CSVs and ../out132 holds the fitted table."""
import numpy as np, pandas as pd, glob
C=dict(Z0=1.58, M2=(286,0.41), S2=(306,0.18), K1=(117,0.09), O1=(349,0.05), f4=221,F4=0.779,f6=3,F6=0.502)
d2r=np.pi/180
def shm(t, c=C):  # t: pandas DatetimeIndex UTC
    jd=(t - pd.Timestamp("2000-01-01 12:00",tz="UTC"))/pd.Timedelta("1D"); T=jd.values/36525
    s=218.3164+481267.8812*T; h=280.4661+36000.7698*T; N=(125.0445-1934.1363*T)*d2r
    ut=(t.hour+t.minute/60+t.second/3600).values; T0=180+15*ut
    V={"M2":2*T0+2*h-2*s,"S2":2*T0,"K1":T0+h-90,"O1":T0+h-2*s+90}
    f={"M2":1.0004-0.0373*np.cos(N)+0.0002*np.cos(2*N),"S2":1,"K1":1.006+0.115*np.cos(N)-0.009*np.cos(2*N),"O1":1.009+0.187*np.cos(N)-0.015*np.cos(2*N)}
    u={"M2":-2.14*np.sin(N),"S2":0,"K1":-8.86*np.sin(N)+0.68*np.sin(2*N),"O1":10.80*np.sin(N)-1.34*np.sin(2*N)}
    arg={k:(V[k]+u[k]-c[k][0])*d2r for k in V}
    z=f["M2"]*c["M2"][1]*np.exp(1j*arg["M2"])+c["S2"][1]*np.exp(1j*arg["S2"])
    R=np.abs(z); ph=np.angle(z)
    hh=c["Z0"]+z.real+f["K1"]*c["K1"][1]*np.cos(arg["K1"])+f["O1"]*c["O1"][1]*np.cos(arg["O1"])
    hh+=c["F4"]*R**2*np.cos(2*ph-c["f4"]*d2r)+c["F6"]*R**3*np.cos(3*ph-c["f6"]*d2r)
    return pd.Series(hh,index=t)
# gauge obs (EA archive, last year)
parts=[pd.read_csv(f,header=None,names=["t","m","v"])[["t","v"]] for f in glob.glob("../data/ea/*.csv")]
g=pd.concat(parts); g["t"]=pd.to_datetime(g.t,utc=True,format="ISO8601"); g["v"]=pd.to_numeric(g.v,errors="coerce")
g=g.dropna().drop_duplicates("t").set_index("t").v.sort_index().resample("15min").mean()+1.32
g[(g<0.41)|(g>3.4)]=np.nan
obs=g.resample("1h").mean().dropna()
# our harmonic table
b=open("../out132/poole_astro_10min.bin","rb").read(); st,step,n=np.frombuffer(b[:24],"<i8"); cm=np.frombuffer(b[24:],"<i2")
ours=pd.Series(cm/100, index=pd.to_datetime(st+step*np.arange(n),unit="s",utc=True))
o=obs["2026-01-01":]
p=shm(o.index); a=ours.reindex(o.index)
rm=lambda x: float(np.sqrt(np.nanmean(x**2)))
print("hours",len(o),"RMSE SHM",rm(o-p),"bias",float((o-p).mean()),"| ours harmonic",rm(o-a),"bias",float((o-a).mean()))
# daily max
print("daily max RMSE SHM",rm(o.resample("1D").max()-p.resample("1D").max()),"ours",rm(o.resample("1D").max()-a.resample("1D").max()))
day=pd.date_range("2026-11-13",periods=144,freq="10min",tz="UTC")
s=shm(day); a=ours.reindex(day)
for i in range(0,144,6): print(day[i].strftime("%H:%M"), round(s.iloc[i],2), round(a.iloc[i],2))
