# music-syncer

Bidirectional wireless mirror of a music library between a Linux laptop and an
Android phone. Records every change in a timestamped journal; syncs when you
tell it to.

See `docs/superpowers/specs/2026-09-28-music-sync-engine-design.md` for the
full design.

## Usage (laptop side)

    pip install -r requirements.txt
    python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"
    python -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/"
    python -m ms.cli log
    python -m ms.cli verify --path "/media/shaarky/Data/Shaarav/my songs/"

Suggested aliases:

    alias msscan='python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"'
    alias msserve='python -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/"'

## Status

Engine + Linux daemon implemented. Android app (Plan 2) pending — it ports the
same engine semantics (scan → journal → merge → apply) over mDNS + HTTP.