import subprocess, numpy as np
D=[0.55,0.5,0.45,0.15,1.0,0.5,0.5]
names=['size','filter','fade','predelay','mix','pdmix','damp']
def cap(p,side='left'):
    subprocess.run(['./grid_oracle_ir','grid/t.f32']+[str(v) for v in p]+[side],check=True)
    return np.fromfile('grid/t.f32',dtype='<f4').reshape(-1,2).astype(np.float64)[:16384]
base=cap(D)
e0=np.sqrt((base**2).sum())
print('default energy',(base**2).sum(axis=0))
for i,n in enumerate(names):
    row=[]
    for v in [0.0,0.25,0.75,1.0]:
        p=list(D);p[i]=v
        y=cap(p)
        row.append('%.2f:%.3f'%(v,np.sqrt(((y-base)**2).sum())/e0))
    print(n.ljust(9),' '.join(row))
