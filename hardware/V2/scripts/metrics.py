import pcbnew,collections,math,sys
b=pcbnew.LoadBoard(sys.argv[1])
L=collections.Counter(); V=collections.Counter()
for t in b.GetTracks():
    n=t.GetNetname()
    if t.GetClass()=='PCB_VIA': V[n]+=1
    else: L[n]+=pcbnew.ToMM(t.GetLength())
pads=collections.defaultdict(list)
for fp in b.GetFootprints():
    for p in fp.Pads():
        if p.GetNetname(): pads[p.GetNetname()].append((pcbnew.ToMM(p.GetPosition().x), pcbnew.ToMM(p.GetPosition().y)))
def mst(pts):
    if len(pts)<2: return 0
    inT={0}; tot=0
    while len(inT)<len(pts):
        d,j=min((math.dist(pts[i],pts[k]),k) for i in inT for k in range(len(pts)) if k not in inT); tot+=d; inT.add(j)
    return tot
for n in sorted(L,key=lambda n:-L[n]):
    if n=='GND': continue
    print(f"{n:11s} ideal {mst(pads[n]):5.1f}  routed {L[n]:5.1f}  vias {V[n]}")
print('signal vias total', sum(v for n,v in V.items() if n!='GND'), '| GND vias', V['GND'])
