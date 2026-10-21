"""Audit and fill the bundled Windows UI catalogs (never runs in the app).

Existing translations win. --fetch sends only missing, checked-in interface copy
to Google's translation service; no account, music library or runtime data is read.
Generated translations are a baseline for human review, not a runtime dependency.
Run without --fetch for an offline completeness/format-placeholder check.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import html
import json
import re
import time
import threading
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'android/app/src/main/res'
OUT = ROOT / 'windows/core/src/desktopMain/resources/i18n/desktop/catalogs'
MANIFEST = OUT.parent / 'interface-copy.json'
TAGS = (ROOT / 'windows/src/main/resources/desktop-languages.txt').read_text().split()
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]')
REQUEST_LOCK = threading.Lock()
NEXT_REQUEST = 0.0
LITERAL = re.compile(r'"((?:\\.|[^"\\\r\n])*)"')
BRANDS = {'Auralis', 'Discord', 'Last.fm', 'ListenBrainz', 'Spotify', 'YouTube', 'YouTube Music', 'OpenAI', 'OpenRouter', 'DeepSeek', 'Google Gemini', 'Gemini', 'Windows', 'VLC', 'LRCLIB', 'Musixmatch', 'Kugou', 'QQ Music', 'NetEase', 'VISIONOS', 'IOS', 'API', 'TTML', 'LRC', 'Romanization', 'Latin', '©'}


def unescape(s):
    out=[]; quoted=False; space=False; i=0
    while i < len(s):
        c=s[i]
        if c == '\\' and i+1 < len(s):
            n=s[i+1]
            if n=='u': out.append(chr(int(s[i+2:i+6],16))); i+=6
            else: out.append({'n':'\n','t':'\t'}.get(n,n)); i+=2
            space=False; continue
        if c=='"': quoted=not quoted
        elif c.isspace() and not quoted:
            if not space: out.append(' ')
            space=True; i+=1; continue
        else: out.append(c)
        space=False; i+=1
    return ''.join(out).strip()


def xml_strings(folder):
    path = RES / folder / 'strings.xml'
    if not path.exists(): return {}
    return {e.attrib['name']: unescape(''.join(e.itertext()))
            for e in ET.parse(path).getroot().findall('string') if e.attrib.get('translatable') != 'false'}


def folders(tag):
    lang, *rest = tag.split('-')
    legacy = {'id':'in','he':'iw'}.get(lang,lang)
    result = [f'values-{legacy}']
    if rest:
        result.append(f'values-b+{legacy}+{rest[0]}' if len(rest[0]) == 4 else f'values-{legacy}-r{rest[0]}')
    return result


def sources():
    copy = set(xml_strings('values').values())
    # Display labels/descriptions supplied to settings rows by shared enums.
    for filename in ['ContentSettings.kt','AppearanceSettings.kt']:
        path=ROOT/'windows/core/src/commonMain/kotlin/com/auralis/music/domain/model'/filename
        for match in LITERAL.finditer(path.read_text(encoding='utf-8')):
            if '$' not in match[1] and re.search('[A-Za-z]',match[1]): copy.add(unescape(match[1]))
    # Static text and display choices in desktop UI files. Metadata is never a source.
    ui = ROOT / 'windows/src/main/kotlin/com/auralis/music/windows'
    excluded = {'dev', 'media'}
    for path in ui.rglob('*.kt'):
        if any(p in excluded for p in path.relative_to(ui).parts): continue
        source = path.read_text(encoding='utf-8')
        if '@Composable' not in source and path.name not in {'DesktopHotkeys.kt','DiscordCardSettings.kt','DesktopAiLyrics.kt','DesktopPreferences.kt','DesktopScrobbling.kt'}: continue
        for match in re.finditer(r'\bdesktopText\(\s*"((?:\\.|[^"\\])*)"',source):
            if '$' not in match[1]: copy.add(unescape(match[1]))
        source = re.sub(r'"""[\s\S]*?"""', '', source)
        source = re.sub(r'(?m)^\s*//.*$', '', source)
        for match in LITERAL.finditer(source):
            raw = match[1]
            if '$' in raw or '\\' in raw or 'http' in raw or '/' in raw or '@' in raw or '{' in raw or '}' in raw: continue
            label = unescape(raw)
            if not re.search(r'[A-Za-z]', label): continue
            if len(label) > 450 or re.search(r'^[a-z][a-zA-Z0-9_.-]*$', label): continue
            if re.search(r'\.(kt|png|jpg|properties|json|ttf|otf|bin|txt|log|exe|wav|m3u)$', label, re.I): continue
            if label.isupper() and len(label) > 12: continue
            if label.startswith(('#','--','org.','com.','Bearer ')): continue
            if label.isupper() and label not in BRANDS: continue
            if label.startswith(('-','.','&','=','<','%')): continue
            # Configuration keys/codes have no spaces and mixed lower/upper case.
            if ' ' not in label and re.search(r'[a-z][A-Z]',label) and label not in BRANDS: continue
            copy.add(label)
    # Parameterized copy is explicit: arguments are substituted after translation.
    copy.update(json.loads((OUT.parent / 'parameterized-copy.json').read_text(encoding='utf-8')))
    return sorted(s for s in copy if s and not s.startswith('@'))


def existing(tag, english, include_generated=True):
    result = {}
    for folder in folders(tag):
        for key, value in xml_strings(folder).items():
            if key in english and value: result[english[key]] = value
    tsv = OUT.parent / 'translations.tsv'
    with tsv.open(encoding='utf-8',newline='') as f:
        for row in csv.DictReader(f,delimiter='\t'):
            value = row.get(tag) or row.get(tag.split('-')[0])
            if value: result[row['en']] = value
    path = OUT / f'{tag}.json'
    if include_generated and path.exists(): result.update(json.loads(path.read_text(encoding='utf-8')))
    return result


def signature(s): return sorted(FORMAT.findall(s))


def nynorsk_values(values):
    cache_path=ROOT/'build/translation-cache/nynorsk.json'
    cache=json.loads(cache_path.read_text(encoding='utf-8')) if cache_path.exists() else {}
    entries=[(k,v) for k,v in values.items() if v not in cache]
    result={k:cache[v] for k,v in values.items() if v in cache}; batch=[]; size=0
    def translate_batch(items):
        arguments=[]
        def protect(source):
            out=[]; start=0
            for token in FORMAT.finditer(source):
                out.append(html.escape(source[start:token.start()]))
                arguments.append(token[0])
                out.append(f'<span data-argument="{len(arguments)-1}"></span>')
                start=token.end()
            out.append(html.escape(source[start:])); return ''.join(out)
        markup=''.join(f'<p data-id="{i}">{protect(v)}</p>' for i,(_,v) in enumerate(items))
        url='https://apertium.org/apy/translate?'+urllib.parse.urlencode({
            'langpair':'nob|nno','q':markup,'markUnknown':'no','format':'html'})
        data=request_json(url)
        document=ET.fromstring('<root>'+data['responseData']['translatedText']+'</root>')
        def restore(node):
            if 'data-argument' in node.attrib: return arguments[int(node.attrib['data-argument'])]
            return (node.text or '')+''.join(restore(child)+(child.tail or '') for child in node)
        translated={int(node.attrib['data-id']):restore(node) for node in document.findall('p')}
        if len(translated)!=len(items): raise ValueError('Nynorsk conversion lost a text boundary')
        checked={}
        for i,(key,source) in enumerate(items):
            target=translated[i]
            if signature(source)!=signature(target):
                tokens=list(FORMAT.finditer(source))
                if not tokens: raise ValueError('Nynorsk conversion introduced a format argument')
                pieces=[]; start=0
                for token in tokens:
                    pieces.append(source[start:token.start()]); pieces.append(token[0]); start=token.end()
                pieces.append(source[start:])
                prose=[(str(i),part.strip()) for i,part in enumerate(pieces) if i%2==0 and part.strip()]
                fragments=translate_batch(prose) if prose else {}
                target=''.join((part[:len(part)-len(part.lstrip())]+fragments[str(i)]+part[len(part.rstrip()):])
                    if str(i) in fragments else part for i,part in enumerate(pieces))
                if signature(source)!=signature(target): raise ValueError('Nynorsk conversion damaged a format argument')
            checked[key]=target
        cache.update({source:checked[key] for key,source in items})
        cache_path.parent.mkdir(parents=True,exist_ok=True)
        cache_path.write_text(json.dumps(cache,ensure_ascii=False),encoding='utf-8')
        return checked
    for item in entries+[None]:
        if batch and (item is None or size+len(item[1])>1800):
            result.update(translate_batch(batch)); batch=[]; size=0
        if item is not None:
            if item[0] in BRANDS: result[item[0]]=item[1]; continue
            batch.append(item); size+=len(item[1])+1
    return result


def request_json(url):
    global NEXT_REQUEST
    for attempt in range(5):
        with REQUEST_LOCK:
            delay=max(0,NEXT_REQUEST-time.monotonic())
            if delay: time.sleep(delay)
            NEXT_REQUEST=time.monotonic()+1.1
        try:
            with urllib.request.urlopen(url,timeout=60) as response: return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 429 and error.code < 500: raise
            with REQUEST_LOCK:
                NEXT_REQUEST=max(NEXT_REQUEST,time.monotonic()+max(60,int(error.headers.get('Retry-After','60'))))
            if attempt == 4: raise


def translate_plain(tag, text):
    target = {'nb-NO':'no','nn':'no','sr-Latn':'sr','fil':'tl'}.get(tag,tag if tag.startswith('zh-') else tag.split('-')[0])
    url = 'https://translate.googleapis.com/translate_a/single?' + urllib.parse.urlencode(
        {'client':'gtx','sl':'en','tl':target,'dt':'t','q':text})
    data=request_json(url)
    return ''.join(part[0] or '' for part in data[0]).strip()


def translate_segments(tag, text):
    # A service may omit a formatting-only argument. Translate the surrounding
    # prose independently in that case, retaining every argument byte-for-byte.
    out=[]; start=0
    for match in list(FORMAT.finditer(text))+[None]:
        end=match.start() if match else len(text); part=text[start:end]
        if re.search('[A-Za-z]',part):
            leading=part[:len(part)-len(part.lstrip())]; trailing=part[len(part.rstrip()):]
            out.append(leading+translate_plain(tag,part.strip())+trailing)
        else: out.append(part)
        if match: out.append(match[0]); start=match.end()
    return ''.join(out)


def fetch_batch(tag, entries):
    masks = []
    def mask(m):
        masks.append(m[0]); return '{{' + str(len(masks)-1) + '}}'
    encoded=[FORMAT.sub(mask,value).replace('\n',' {{99999}} ') for value in entries]
    text = '\n'.join(encoded)
    target = {'nb-NO':'no','nn':'no','sr-Latn':'sr','zh-TW':'zh-TW','zh-CN':'zh-CN','fil':'tl'}.get(tag,tag.split('-')[0])
    url = 'https://translate.googleapis.com/translate_a/single?' + urllib.parse.urlencode(
        {'client':'gtx','sl':'en','tl':target,'dt':'t','q':text})
    for attempt in range(5):
        try:
            data=request_json(url)
            translated = ''.join(part[0] or '' for part in data[0])
            lines=translated.strip().splitlines()
            if len(lines)==len(entries): pairs=list(enumerate(lines))
            else:
                indexed='\n'.join(f'ZXID{i}DIXZ '+value for i,value in enumerate(encoded))
                indexed_url='https://translate.googleapis.com/translate_a/single?'+urllib.parse.urlencode(
                    {'client':'gtx','sl':'en','tl':target,'dt':'t','q':indexed})
                data=request_json(indexed_url)
                translated=''.join(part[0] or '' for part in data[0])
                parts=re.split(r'ZXID\s*(\d+)\s*DIXZ\s*',translated,flags=re.I)
                pairs=[(int(parts[i]),parts[i+1]) for i in range(1,len(parts),2)]
            result = {}
            for n,raw in pairs:
                value=raw.strip()
                value=re.sub(r'\s*\{\{\s*99999\s*\}\}\s*','\n',value)
                value=re.sub(r'\{\{\s*(\d+)\s*\}\}',lambda m:masks[int(m[1])],value)
                if n < len(entries) and value and signature(value) == signature(entries[n]) and not re.search(r'ZXID|\{\{\s*\d',value,re.I):
                    result[entries[n]]=value
            # Keep every valid segment. Retry only a damaged/missing entry rather
            # than repeatedly retranslating an otherwise valid batch.
            for entry in entries:
                if entry not in result: result[entry]=translate_segments(tag,entry)
            return nynorsk_values(result) if tag=='nn' else result
        except Exception as error:
            if isinstance(error,urllib.error.HTTPError): raise
            if len(entries) > 1:
                middle=len(entries)//2
                return {**fetch_batch(tag,entries[:middle]),**fetch_batch(tag,entries[middle:])}
            try:
                value=translate_segments(tag,entries[0])
                if value and signature(value)==signature(entries[0]): return {entries[0]:value}
            except Exception: pass
            if attempt == 4: raise
            time.sleep(min(2**attempt,8))


def complete(tag, copy, fetch):
    english=xml_strings('values')
    catalog=existing(tag,english)
    for value in copy:
        if value in BRANDS or not re.search('[A-Za-z]',FORMAT.sub('',value)): catalog.setdefault(value,value)
    # Regions inherit the completed parent, while retaining their existing overrides.
    parent=tag.split('-')[0]
    if tag != parent and (OUT / f'{parent}.json').exists():
        catalog={**json.loads((OUT / f'{parent}.json').read_text(encoding='utf-8')),**catalog}
    missing=[value for value in copy if not catalog.get(value) or signature(value)!=signature(catalog[value])]
    if fetch:
        batch=[]; size=0
        for value in missing + [None]:
            if batch and (value is None or size + len(value) > 4300):
                catalog.update(fetch_batch(tag,batch)); save(tag,catalog); batch=[]; size=0
            if value is not None: batch.append(value); size+=len(value)+20
        save(tag,catalog)
    return tag, len(missing)


def save(tag, catalog):
    if tag == 'sr-Latn':
        cyrillic='АБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШ'
        latin=['A','B','V','G','D','Đ','E','Ž','Z','I','J','K','L','Lj','M','N','Nj','O','P','R','S','T','Ć','U','F','H','C','Č','Dž','Š']
        alphabet={**dict(zip(cyrillic,latin)),**dict(zip(cyrillic.lower(),[x.lower() for x in latin]))}
        catalog={k:''.join(alphabet.get(c,c) for c in v) for k,v in catalog.items()}
    OUT.mkdir(parents=True,exist_ok=True)
    path=OUT / f'{tag}.json'
    temporary=path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(catalog,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    temporary.replace(path)


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--fetch',action='store_true'); parser.add_argument('--tags',nargs='*'); parser.add_argument('--convert-nynorsk',action='store_true'); args=parser.parse_args()
    if args.convert_nynorsk:
        original=json.loads((OUT/'nn.json').read_text(encoding='utf-8'))
        contributed=existing('nn',xml_strings('values'),include_generated=False)
        converted=nynorsk_values({k:v for k,v in original.items() if k not in contributed})
        save('nn',{**original,**converted,**contributed})
        print(f'Converted {len(converted)} baseline strings to Nynorsk; preserved contributed translations.',flush=True)
        return
    copy=sources(); MANIFEST.write_text(json.dumps(copy,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    tags=args.tags or [tag for tag in TAGS if not tag.startswith('en')]
    print(f'Interface inventory: {len(copy)} strings',flush=True)
    incomplete=[]
    for group in ([tag for tag in tags if '-' not in tag],[tag for tag in tags if '-' in tag]):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            futures={pool.submit(complete,tag,copy,args.fetch):tag for tag in group}
            for future in concurrent.futures.as_completed(futures):
                try:
                    tag,missing=future.result(); print(f'{tag}: {missing} missing'+(' (filled)' if args.fetch else ''),flush=True)
                    if missing and not args.fetch: incomplete.append(tag)
                except Exception as e: print(f'FAILED {futures[future]}: {e}',flush=True); raise
    if incomplete: raise SystemExit(1)


if __name__ == '__main__': main()
