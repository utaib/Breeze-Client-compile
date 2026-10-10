from PIL import Image
import random, base64, io

S=16
def img(): return Image.new('RGBA',(S,S),(0,0,0,0))
def px(im,x,y,c): im.putpixel((x%S,y%S),c)
def H(h,a=255):
    h=h.lstrip('#'); return (int(h[0:2],16),int(h[2:4],16),int(h[4:6],16),a)

def noise_fill(im,pal,seed):
    r=random.Random(seed)
    for y in range(S):
        for x in range(S):
            px(im,x,y,r.choice(pal))
    return im

def log_side(base,dark,light,darker,seed):
    im=img(); r=random.Random(seed)
    for x in range(S):
        col=r.choice([base,base,base,light,dark])
        for y in range(S):
            c=col
            if r.random()<0.20: c=dark
            elif r.random()<0.12: c=light
            elif r.random()<0.05: c=darker
            px(im,x,y,c)
    # long vertical bark grooves
    for gx in [1,4,5,9,12,14]:
        c=darker if gx%2 else dark
        y=r.randrange(0,6); h=r.randrange(7,14)
        for yy in range(y,min(S,y+h)): px(im,gx,yy,c)
    return im

def log_top(bark,ring1,ring2,core):
    im=img()
    for y in range(S):
        for x in range(S):
            d=max(abs(x-7.5),abs(y-7.5))
            if d>6.5: c=bark
            elif d>5.2: c=ring2
            elif d>3.4: c=ring1
            elif d>2.0: c=ring2
            else: c=core
            px(im,x,y,c)
    return im

def leaves(pal,holes,seed):
    im=img(); r=random.Random(seed)
    for y in range(S):
        for x in range(S):
            if r.random()<holes: continue
            px(im,x,y,r.choice(pal))
    # clumping: darken a few 2x2 patches for depth
    for _ in range(9):
        x=r.randrange(S-1); y=r.randrange(S-1)
        c=pal[-1]
        for dx in range(2):
            for dy in range(2):
                if im.getpixel(((x+dx)%S,(y+dy)%S))[3]: px(im,x+dx,y+dy,c)
    return im

def dirt(seed=5):
    return noise_fill(img(),[H('#866043'),H('#866043'),H('#7A5539'),H('#96704C'),H('#6F4E34'),H('#9B7752')],seed)

def grass_top(seed=7):
    return noise_fill(img(),[H('#79C05A'),H('#79C05A'),H('#6FB350'),H('#86CB63'),H('#68A94A'),H('#8FD46C')],seed)

def grass_side(seed=9):
    im=dirt(seed); r=random.Random(seed+1)
    gp=[H('#79C05A'),H('#6FB350'),H('#86CB63'),H('#68A94A')]
    for x in range(S):
        d=r.choice([2,3,3,4,3,2,4])
        for y in range(d): px(im,x,y,r.choice(gp))
        if r.random()<0.45: px(im,x,d,r.choice(gp))
    return im

def stone(seed=11):
    return noise_fill(img(),[H('#7F7F7F'),H('#7F7F7F'),H('#757575'),H('#8A8A8A'),H('#6E6E6E'),H('#929292')],seed)

def cobble(seed=13):
    im=noise_fill(img(),[H('#6E6E6E'),H('#787878')],seed)
    r=random.Random(seed)
    for _ in range(9):
        cx=r.randrange(S); cy=r.randrange(S); w=r.randrange(3,6); h=r.randrange(3,5)
        c=r.choice([H('#8A8A8A'),H('#9A9A9A'),H('#7F7F7F')])
        for dx in range(w):
            for dy in range(h): px(im,cx+dx,cy+dy,c)
    for _ in range(26):
        px(im,r.randrange(S),r.randrange(S),H('#5A5A5A'))
    return im

def short_grass(seed=17):
    im=img(); r=random.Random(seed)
    gp=[H('#7BBF57'),H('#6BAA48'),H('#8ACF66')]
    for bx in range(1,S,3):
        h=r.randrange(5,11); lean=r.choice([-1,0,0,1])
        for i in range(h):
            x=bx+int(lean*(i/h)*2)
            px(im,x,S-1-i,r.choice(gp))
        px(im,bx+lean,S-h,r.choice(gp))
    return im

TILES=[
  ('oak_log',      log_side(H('#6B5433'),H('#55432A'),H('#7C6039'),H('#48381F'),1)),
  ('oak_leaves',   leaves([H('#4F8A2C'),H('#4F8A2C'),H('#5C9C34'),H('#3F7423'),H('#6BAE3C'),H('#35621C')],0.05,2)),
  ('spruce_log',   log_side(H('#3B2C1B'),H('#2C2014'),H('#4A3823'),H('#221809'),3)),
  ('spruce_leaves',leaves([H('#2F5A34'),H('#2F5A34'),H('#376A3C'),H('#254A2A'),H('#43804A'),H('#1D3A21')],0.08,4)),
  ('birch_log',    log_side(H('#D6D0C4'),H('#B9B2A4'),H('#E4E0D6'),H('#3A3A34'),5)),
  ('birch_leaves', leaves([H('#7BAE4E'),H('#7BAE4E'),H('#8ABF5B'),H('#6A9942'),H('#96CC67'),H('#5A8536')],0.07,6)),
  ('grass_side',   grass_side()),
  ('grass_top',    grass_top()),
  ('dirt',         dirt()),
  ('stone',        stone()),
  ('cobble',       cobble()),
  ('short_grass',  short_grass()),
  ('oak_top',      log_top(H('#6B5433'),H('#B08A4E'),H('#9A7742'),H('#C39B5C'))),
]

def tint(im,mul,blend=None,amt=0.0):
    o=im.copy(); pxs=o.load()
    for y in range(S):
        for x in range(S):
            r,g,b,a=pxs[x,y]
            if a==0: continue
            r=int(r*mul[0]); g=int(g*mul[1]); b=int(b*mul[2])
            if blend:
                r=int(r*(1-amt)+blend[0]*amt); g=int(g*(1-amt)+blend[1]*amt); b=int(b*(1-amt)+blend[2]*amt)
            pxs[x,y]=(min(r,255),min(g,255),min(b,255),a)
    return o

NEAR=(0.52,0.62,0.86)
MID =(0.46,0.57,0.83)
FAR =(0.40,0.50,0.78)
FOGC=(27,76,100)

GROUND={6,7,8,9,10}   # grass_side, grass_top, dirt, stone, cobble — sit under the rail
def dim(mul,i): return tuple(v*(0.60 if i in GROUND else 1.0) for v in mul)
rows=[
  ('near', lambda i: tint(i,NEAR)),
  ('mid',  lambda i: tint(i,MID,FOGC,0.34)),
  ('far',  lambda i: tint(i,FAR,FOGC,0.60)),
]
atlas=Image.new('RGBA',(S*len(TILES),S*len(rows)),(0,0,0,0))
MULS=[(NEAR,None,0.0),(MID,FOGC,0.34),(FAR,FOGC,0.60)]
for ri,(mul,bl,amt) in enumerate(MULS):
    for ci,(name,im) in enumerate(TILES):
        atlas.paste(tint(im,dim(mul,ci),bl,amt),(ci*S,ri*S))
atlas.save('/home/claude/tex/atlas.png')
b=io.BytesIO(); atlas.save(b,'PNG',optimize=True)
open('/home/claude/tex/atlas_b64.txt','w').write('data:image/png;base64,'+base64.b64encode(b.getvalue()).decode())
print('tiles', [t[0] for t in TILES])
print('atlas', atlas.size, 'b64', len(open('/home/claude/tex/atlas_b64.txt').read()))
atlas.resize((atlas.width*6,atlas.height*6),Image.NEAREST).save('/home/claude/tex/atlas_big.png')


# ---------------- isometric face atlas -------------------------------------
# rows = cube faces (top / left / right), so shading is baked, not per-frame.
NIGHT=(0.46,0.58,0.84)
FACE=[('top',1.00),('left',0.76),('right',0.55)]
iso=Image.new('RGBA',(S*len(TILES),S*len(FACE)),(0,0,0,0))
for ri,(fn,k) in enumerate(FACE):
    mul=tuple(v*k for v in NIGHT)
    for ci,(name,im) in enumerate(TILES):
        iso.paste(tint(im,mul),(ci*S,ri*S))
iso.save('/home/claude/tex/iso.png')
b=io.BytesIO(); iso.save(b,'PNG',optimize=True)
open('/home/claude/tex/iso_b64.txt','w').write('data:image/png;base64,'+base64.b64encode(b.getvalue()).decode())
iso.resize((iso.width*6,iso.height*6),Image.NEAREST).save('/home/claude/tex/iso_big.png')
print('iso atlas',iso.size)

# ---------------- modern blocks (1.20 – 1.21.5 era) ------------------------
def water(seed=31):
    im=img(); r=random.Random(seed)
    pal=[H('#3F76E4'),H('#3F76E4'),H('#3A6BD4'),H('#4A83EE'),H('#3560C4'),H('#5590F2')]
    for y in range(S):
        band=r.choice(pal)
        for x in range(S):
            px(im,x,y,band if r.random()<0.55 else r.choice(pal))
    for _ in range(7):
        y=r.randrange(S); w=r.randrange(3,8); x0=r.randrange(S)
        for i in range(w): px(im,x0+i,y,H('#6FA6F7'))
    return im

def leaf_litter(seed=33):
    im=img(); r=random.Random(seed)
    pal=[H('#8A5A2B'),H('#A2六'.replace('六','6')+'3A'),H('#6E4520'),H('#B0722F'),H('#7E5A32')]
    pal=[H('#8A5A2B'),H('#A26A3A'),H('#6E4520'),H('#B0722F'),H('#7E5A32'),H('#5C3A1C')]
    for y in range(S):
        for x in range(S):
            if r.random()<0.42: continue          # gaps show the block beneath
            px(im,x,y,r.choice(pal))
    return im

def moss(seed=35):
    im=noise_fill(img(),[H('#5A7A32'),H('#5A7A32'),H('#4E6C2A'),H('#66883A'),H('#435E22'),H('#71953F')],seed)
    r=random.Random(seed)
    for _ in range(10):
        x=r.randrange(S); y=r.randrange(S)
        for dx in range(2):
            for dy in range(2): px(im,x+dx,y+dy,H('#3E5820'))
    return im

def firefly_bush(seed=37):
    im=img(); r=random.Random(seed)
    pal=[H('#3B5A28'),H('#314D20'),H('#456A2F')]
    for y in range(4,S):
        w=int((y-3)*0.9)
        for x in range(8-w,8+w):
            if r.random()<0.25: continue
            px(im,x,y,r.choice(pal))
    for _ in range(4):                              # the glow the bush is named for
        px(im,r.randrange(4,12),r.randrange(6,14),H('#F2E08A'))
    return im

def pale_log(seed=39):
    return log_side(H('#B6B2A9'),H('#928E85'),H('#C7C3BA'),H('#5C5951'),seed)

def pale_leaves(seed=41):
    return leaves([H('#8E998C'),H('#8E998C'),H('#9BA697'),H('#7C8779'),H('#A8B2A3'),H('#6B7568')],0.07,seed)

TILES += [
  ('water',        water()),
  ('leaf_litter',  leaf_litter()),
  ('moss',         moss()),
  ('firefly_bush', firefly_bush()),
  ('pale_log',     pale_log()),
  ('pale_leaves',  pale_leaves()),
  ('pale_top',     log_top(H('#DCD9D2'),H('#C9C5BB'),H('#B4B0A6'),H('#E4E1DA'))),
]

iso2=Image.new('RGBA',(S*len(TILES),S*len(FACE)),(0,0,0,0))
for ri,(fn,k) in enumerate(FACE):
    mul=tuple(v*k for v in NIGHT)
    for ci,(name,im) in enumerate(TILES):
        iso2.paste(tint(im,mul),(ci*S,ri*S))
iso2.save('/home/claude/tex/iso.png')
b=io.BytesIO(); iso2.save(b,'PNG',optimize=True)
open('/home/claude/tex/iso_b64.txt','w').write('data:image/png;base64,'+base64.b64encode(b.getvalue()).decode())
iso2.resize((iso2.width*5,iso2.height*5),Image.NEAREST).save('/home/claude/tex/iso_big.png')
print('iso v2', iso2.size, [t[0] for t in TILES])
