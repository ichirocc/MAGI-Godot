import csv,sys,statistics as st
rows=list(csv.DictReader(open(sys.argv[1],encoding='utf-8')))
pairs={}
for r in rows:
    k=(r['case'],r['seed']); pairs.setdefault(k,{})[r['arm']]=r
def f(x): return float(x)
P=[(k,v['old'],v['new']) for k,v in pairs.items() if 'old' in v and 'new' in v]
n=len(P); print(f"pairs={n}")
def lex(o,nw):
    # 辞書式: 必須ゼロ達成 → 必須件数 → 必須加重 → ソフト加重 → 希望充足率(高いほど良) → 変更セル数
    a=[(f(o['hard'])==0), -f(o['hard']), -f(o['hardW']), -f(o['softW']), f(o['wishRate']), -f(o['changed'])]
    b=[(f(nw['hard'])==0), -f(nw['hard']), -f(nw['hardW']), -f(nw['softW']), f(nw['wishRate']), -f(nw['changed'])]
    for x,y in zip(a,b):
        if y>x: return 1
        if y<x: return -1
    return 0
better=sum(1 for _,o,nw in P if lex(o,nw)>0); worse=sum(1 for _,o,nw in P if lex(o,nw)<0)
hz_old=sum(1 for _,o,_n in P if f(o['hard'])==0)/n; hz_new=sum(1 for _,_o,nw in P if f(nw['hard'])==0)/n
regress=[k for k,o,nw in P if f(o['hard'])==0 and f(nw['hard'])>0]
hard_worse=[k for k,o,nw in P if f(nw['hard'])>f(o['hard'])]
q_imp=[(f(o['weighted'])-f(nw['weighted']))/f(o['weighted']) if f(o['weighted'])>0 else 0.0 for _,o,nw in P]
wish_old=st.mean(f(o['wishRate']) for _,o,_n in P); wish_new=st.mean(f(nw['wishRate']) for _,_o,nw in P)
ms_old=[f(o['ms']) for _,o,_n in P]; ms_new=[f(nw['ms']) for _,_o,nw in P]
def p90(a): a=sorted(a); return a[int(0.9*(len(a)-1))]
mem_old=max(f(o['peakMB']) for _,o,_n in P); mem_new=max(f(nw['peakMB']) for _,_o,nw in P)
to_old=sum(int(o['timeout']) for _,o,_n in P); to_new=sum(int(nw['timeout']) for _,_o,nw in P)
exc=sum(int(nw['exception']) for _,_o,nw in P); oob=sum(int(nw['oob']) for _,_o,nw in P); mm=sum(int(nw['mismatch']) for _,_o,nw in P)
repro_bad=sum(1 for _,_o,nw in P if nw['repro']=='DIFF'); repro_n=sum(1 for _,_o,nw in P if nw['repro'] in('same','DIFF'))
# 重大ケース下位10%: 旧の weighted が最悪の 10% の試行で new<=old
worst=sorted(P,key=lambda x:-f(x[1]['weighted']))[:max(1,n//10)]
worst_ok=all(f(nw['weighted'])<=f(o['weighted']) for _,o,nw in worst)
big_regress=[k for k,o,nw in P if f(nw['weighted'])>f(o['weighted'])*1.10+1e-9]
mean_q=st.mean(q_imp)*100; med_q=st.median(q_imp)*100
mean_s=(st.mean(ms_old)-st.mean(ms_new))/st.mean(ms_old)*100; med_s=(st.median(ms_old)-st.median(ms_new))/st.median(ms_old)*100
print(f"辞書式: 新が良い {better} / 同等 {n-better-worse} / 旧が良い {worse}")
print(f"必須違反ゼロ達成率: 旧 {hz_old*100:.1f}% → 新 {hz_new*100:.1f}%")
print(f"必須違反退行(旧ゼロ→新>0): {len(regress)} 件 {regress[:5]} / 必須件数が増えた試行: {len(hard_worse)}")
print(f"品質改善率(weighted): 平均 {mean_q:+.2f}% 中央値 {med_q:+.2f}%")
print(f"希望充足率: 旧 {wish_old*100:.2f}% → 新 {wish_new*100:.2f}%")
print(f"速度: 平均 {st.mean(ms_old):.0f}→{st.mean(ms_new):.0f}ms ({mean_s:+.1f}%) 中央値 {st.median(ms_old):.0f}→{st.median(ms_new):.0f}ms ({med_s:+.1f}%) p90 {p90(ms_old):.0f}→{p90(ms_new):.0f}ms")
print(f"最大メモリ比: {mem_new/mem_old if mem_old else 0:.2f} 倍 ({mem_old:.0f}→{mem_new:.0f}MB) タイムアウト 旧{to_old} 新{to_new}")
print(f"安定性: 例外 {exc} 範囲外 {oob} 評価不一致 {mm} 再現性 {repro_n-repro_bad}/{repro_n}")
print(f"下位10%品質 旧以上: {worst_ok} / 個別 10% 超退行: {len(big_regress)} 件 {big_regress[:5]}")
gates={'退行ゼロ':len(regress)==0 and worst_ok and len(big_regress)==0,'品質≥10%':mean_q>=10 and med_q>=10 and hz_new>=hz_old and wish_new>=wish_old-1e-9,
 '速度≥10%':mean_s>=10 and med_s>=10 and p90(ms_new)<=p90(ms_old) and mem_new<=mem_old*1.2 and to_new<=to_old,'安定性':exc==0 and oob==0 and mm==0 and repro_bad==0}
print("ゲート:", {k:('合格' if v else '不合格') for k,v in gates.items()}, "→", "合格" if all(gates.values()) else "不合格")
# per-category breakdown
from collections import defaultdict
cat=defaultdict(list)
for (k,o,nw) in P: cat[(o['size'],o['cat'])].append(((f(o['weighted'])-f(nw['weighted']))/f(o['weighted']) if f(o['weighted'])>0 else 0, f(o['hard']),f(nw['hard']), f(o['ms']),f(nw['ms'])))
print("--- 分類別 (size,cat): 品質改善% / 必須 旧→新(平均) / ms 旧→新(平均)")
for k,v in sorted(cat.items()):
    print(f"  {k[0]:6} {k[1]:10} n={len(v):3} q={st.mean(x[0] for x in v)*100:+6.2f}% hard {st.mean(x[1] for x in v):5.2f}->{st.mean(x[2] for x in v):5.2f} ms {st.mean(x[3] for x in v):6.0f}->{st.mean(x[4] for x in v):6.0f}")
