# Graph Report - ilker  (2026-11-27)

## Corpus Check
- Corpus is ~8,252 words - fits in a single context window. You may not need a graph.

## Summary
- 130 nodes · 304 edges · 6 communities
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 4 edges (avg confidence: 0.75)
- Token cost: 115,169 input · 0 output

## Community Hubs (Navigation)
- Brain Persistence & Export
- Chat UI & Moderation
- Language Model Training
- Chat Rooms
- Memory & Reply Engine
- Firebase Auth & Bootstrap

## God Nodes (most connected - your core abstractions)
1. `dinle() pool listener` - 21 edges
2. `Brain state B` - 18 edges
3. `ilkerCevabi() reply engine` - 14 edges
4. `odaAc()` - 14 edges
5. `geriUygula() apply feedback` - 13 edges
6. `yazim submit handler (send message)` - 12 edges
7. `statGuncelle()` - 11 edges
8. `ac() deserialize brain` - 10 edges
9. `benimki()` - 10 edges
10. `beyniKur()` - 10 edges

## Surprising Connections (you probably didn't know these)
- `benzerler() nearest stems` --calls--> `kok() Turkish stemmer`  [EXTRACTED]
  index.html → index.html  _Bridges community 2 → community 4_
- `geriUygula() apply feedback` --calls--> `ogrenmeyeDeger() repeat limiter`  [EXTRACTED]
  index.html → index.html  _Bridges community 1 → community 4_
- `ac() deserialize brain` --calls--> `bosBeyin()`  [EXTRACTED]
  index.html → index.html  _Bridges community 2 → community 0_
- `dinle() pool listener` --calls--> `ogren() learn sentence`  [EXTRACTED]
  index.html → index.html  _Bridges community 2 → community 1_
- `geriUygula() apply feedback` --calls--> `kaydet() debounced save`  [EXTRACTED]
  index.html → index.html  _Bridges community 4 → community 0_

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Sentence learning pipeline** — index_ogren, index_ngramogren, index_skipgramogren, index_egit, index_parcala [EXTRACTED 1.00]
- **Reply generation (memory then generative fallback)** — index_ilkercevabi, index_bilesikvektor, index_uret, index_nndagilim, index_ngramdagilim [EXTRACTED 1.00]
- **Community feedback loop via shared pool** — index_geritak, index_gerigonder, index_dinle, index_geriuygula, index_state_hafiza [INFERRED 0.85]

## Communities (6 total, 0 thin omitted)

### Community 0 - "Brain Persistence & Export"
Cohesion: 0.11
Nodes (25): ac() deserialize brain, benzerGoster(), beyniKur(), Brain export handler, egriCiz() loss curve, Brain import handler (iceSec.onchange), idbAc(), idbOku() (+17 more)

### Community 1 - "Chat UI & Moderation"
Cohesion: 0.13
Nodes (27): aktifKutu(), balonYap(), benimki(), biriktir(), Profanity prefix filter (KUFUR), Send rate limit (BEKLEME 2s), ciftDegeri(), dinle() pool listener (+19 more)

### Community 2 - "Language Model Training"
Cohesion: 0.14
Nodes (24): altOrnekAtla() subsampling, bosBeyin(), Attention + residual MLP neural head, Bigram/trigram synapse model, egit() backprop training, embOf(), ileri() forward pass, kelimeId() (+16 more)

### Community 3 - "Chat Rooms"
Cohesion: 0.27
Nodes (13): akisEkle(), listeCiz(), odaAc(), odaBasCiz(), odaKapat(), odalarimiKaydet(), odayaGir(), sekmeSec() tabs (+5 more)

### Community 4 - "Memory & Reply Engine"
Cohesion: 0.31
Nodes (12): benzerler() nearest stems, bilesikVektor(), cumleVektor() sentence vector, dot(), geriUygula() apply feedback, hafizaAra(), hafizaEkle(), icerikId() (+4 more)

### Community 5 - "Firebase Auth & Bootstrap"
Cohesion: 0.19
Nodes (12): agBaslat() bootstrap, alanYaz(), Admin ban handler, Firebase Realtime Database + Google Auth, FIREBASE_CONFIG, girisHata(), hesapDoldur(), odalarimiYukle() (+4 more)

## Knowledge Gaps
- **7 isolated node(s):** `Brain export handler`, `OGRENSAYAC learn counter`, `FIREBASE_CONFIG`, `Header with pulse and stats summary`, `Tabs ILKER/ORTAK/ODALAR` (+2 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 14 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `dinle() pool listener` connect `Chat UI & Moderation` to `Brain Persistence & Export`, `Language Model Training`, `Memory & Reply Engine`, `Firebase Auth & Bootstrap`?**
  _High betweenness centrality (0.262) - this node is a cross-community bridge._
- **What connects `Brain export handler`, `OGRENSAYAC learn counter`, `FIREBASE_CONFIG` to the rest of the system?**
  _7 weakly-connected nodes found - possible missing edges._
- **Should `Brain Persistence & Export` be split into smaller, more focused modules?**
  _Cohesion score 0.11494252873563218 - nodes in this community are weakly interconnected._
- **Why does `Brain state B` connect `Language Model Training` to `Brain Persistence & Export`, `Memory & Reply Engine`?**
  _High betweenness centrality (0.146) - this node is a cross-community bridge._
- **Should `Chat UI & Moderation` be split into smaller, more focused modules?**
  _Cohesion score 0.1330049261083744 - nodes in this community are weakly interconnected._
- **Why does `beyniKur()` connect `Brain Persistence & Export` to `Chat UI & Moderation`, `Language Model Training`, `Chat Rooms`?**
  _High betweenness centrality (0.127) - this node is a cross-community bridge._
- **Should `Language Model Training` be split into smaller, more focused modules?**
  _Cohesion score 0.14245014245014245 - nodes in this community are weakly interconnected._