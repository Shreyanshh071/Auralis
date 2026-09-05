import urllib.request
import urllib.parse
import json

BASE_URL = "https://lyrics-api.boidu.dev/getLyrics"
headers = {"User-Agent": "Mozilla/5.0"}

songs = [
    ("Creep", "Radiohead"),
    ("Love Me Not", "Ravyn Lenae"),
    ("Birds of a Feather", "Billie Eilish"),
    ("Blinding Lights", "The Weeknd"),
    ("Die With A Smile", "Lady Gaga"),
    ("Espresso", "Sabrina Carpenter")
]

for title, artist in songs:
    url = f"{BASE_URL}?s={urllib.parse.quote(title)}&a={urllib.parse.quote(artist)}"
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read())
            has_ttml = bool(data.get("ttml"))
            has_qrc = bool(data.get("qrc"))
            has_lrc = bool(data.get("lrc"))
            name = data.get("name") or data.get("trackName")
            print(f"{title} - {artist}: ttml={has_ttml}, qrc={has_qrc}, lrc={has_lrc}, name={name}")
            if has_ttml:
                with open(f"scratch/{title.lower().replace(' ', '_')}.ttml", "w", encoding="utf-8") as out:
                    out.write(data["ttml"])
    except Exception as e:
        print(f"{title} - {artist}: error {e}")
