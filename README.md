# İlker Drive

Android için açık dünya araba sürme oyunu. Sonsuz, prosedürel olarak üretilen
bir şehir ve kırsalda serbest sürüş: şerit çizgili yollar, kavşaklar, gökdelenler,
ağaçlar, sokak lambaları, trafik ve tam bir gündüz–gece döngüsü.

Oyun motoru dahil her şey bu depodaki Java kaynağından geliyor. Hiçbir hazır
oyun motoru, hiçbir 3B model dosyası, hiçbir doku, hiçbir ses dosyası yok —
araçların gövdeleri, binalar, arazi ve motor sesi hepsi kod içinde üretiliyor.
Bu yüzden APK sadece **98 KB**.

| | |
|---|---|
| ![Şehir, gündüz](docs/hud-day.png) | ![Şehir, gece](docs/hud-night.png) |
| ![Drift](docs/hud-drift.png) | ![Garaj](docs/garage.png) |

## Kurulum

`dist/ilker-drive-1.4.apk` dosyasını telefonuna kopyala ve aç. Android
"bilinmeyen kaynaklardan yükleme" izni isteyecek; tarayıcına veya dosya
yöneticine bu izni ver.

Android 5.0 (API 21) ve üzeri, OpenGL ES 2.0 destekleyen her cihazda çalışır.
Oyun yatay moda kilitlidir.

## Kontroller

| Kontrol | Yer |
|---|---|
| Direksiyon | Sol alt köşenin tamamı direksiyon bölgesi. Ortadan solda kalan dokunuş sola, sağda kalan sağa çevirir — ve **ne kadar dışta basarsan o kadar çok kırar**, yani düz yolda küçük düzeltme yapabilirsin |
| Gaz / Fren | Sağ alttaki yuvarlak düğmeler |
| El freni (drift) | Frenin üstündeki turuncu düğme |
| KAM | Kamerayı değiştirir: takip → kaput → geniş açı |
| ARAC | Garajdaki sekiz araç arasında geçiş yapar |
| ISIK | Far modu: otomatik → kısa → uzun → kapalı |
| EGIM | Telefonu yana yatırarak direksiyon. Açtığın andaki duruşun nötr kabul edilir |
| SIFIR | Takılırsan seni en yakın yola geri koyar |

Sol üstte pusula gibi dönen bir mini harita, altında mesafe / hız rekoru / saat,
altta ortada hız paneli var. Sarı noktalar trafikteki diğer araçlar. Ekranın
ortası bilerek boş bırakıldı — bakman gereken yer orası.

## Sürüş

Araç **iki akslı bir lastik modeliyle** sürülüyor. Ön ve arka aksın kendi kayma
açısı, kendi tutuş tavanı var; dönme hızı ise direksiyonun bir fonksiyonu değil,
kendi ataleti olan gerçek bir durum. Fark tam olarak şurada: araç dönmeye
başladığında o dönüşü **taşır**, direksiyonu bıraksan bile devam eder — yani
kaymayı toparlaman gerekir, kayma kendi kendine geçmez.

Arka aksın tutuşu önden düşük olan araç savrulur. Drift araçlarının tanımı bu:
`DUMAN DRIFT`'in önü 5.9, arkası 3.45 — arka önce pes eder. `KLASIK SEDAN`'ın
iki aksı neredeyse eşit, o yüzden savrulmak yerine dışarı kaçar.

**Drifti gazla tutarsın.** Kayan bir lastiğin gücü yere basacak hâli kalmaz,
patinaj yapan bir lastiğin de yanal tutuşu kalmaz: kayma → patinaj → daha çok
kayma. Gazı kestiğin an döngü kapanır ve arka toparlanır. El freni de sopayı
elinde tutar — arka aksın tutuşunu bir anda üçte birine indirir.

Ters direksiyona bir miktar **yardım** var; kaymanın yönünde direksiyon
kırdığında tekerlekler senin bastığından biraz fazlasını çevirir. Telefonda
başparmağın yolu uzun, bu yardım olmadan drift yakalanacak bir şey olmaz. Sen ne
kadar çok kırarsan yardım o kadar azalır, yani kontrol hep sende kalır.

Dönme hızının bir tavanı var (2.35 rad/sn). Bu sayede en kötü savrulmada bile
araç senin tepki veremeyeceğin hızda dönmez — tam kilit + el freni + tam gazla
girdiğin bir savrulmayı bir saniye geç bile olsa toparlayabilirsin.

**Drift puanı**: açı × hız × çarpan. Çarpan kaymayı tuttukça iki saniyede bir
büyür, x5'e kadar. Savrulmayı bir yandan öbür yana çevirdiğinde sayaç durmaz —
çevirme işin beceri isteyen kısmı, orada zinciri kırmak yanlış olurdu. Araç
düzeldiğinde puan hanene yazılır, bir yere çarparsan yanar.

Motor **gerçek bir şanzımandan** çekiyor: bir tork eğrisi, içinde bulunduğu
vitesin oranıyla çarpılıyor. Birinci vites lastikleri yener, altıncı zar zor
çeker, vites değişiminde tork kesilir ve devir düşer — duyduğun vites
değişimi aracın gerçekten yaptığı vites değişimi. Bu yüzden araçlar birbirinden
ayırt edilebiliyor: GT 3.3 saniyede 100'e çıkarken hatchback 9.6 saniye alıyor.

Gövde zemine yapışık değil: kendi dikey hızını taşıyor. **Kaldırımdan hızlı
inersen araç havalanır**, yerçekimi geri indirir, yaylar oturur. Yerçekimi
yokuş boyunca da etki ediyor — yokuş yukarı yavaşlarsın, aşağı hızlanırsın,
ve el freni çekmezsen park ettiğin araç geri kaçar.

Bunun yanında:

- **Ağırlık transferi** — hızlanırken ön hafifler, frende arka hafifler; aynı
  virajı gazda ve gaz kesikken almak iki ayrı viraj.
- **Patinaj** — güçlü araçlarda dururken tam gaz lastikleri döndürür.
- **Fren kilitlenmesi** — sert frende tekerler kilitlenir, direksiyon işlemez
  olur ve ön lastikler iz bırakır.
- **Lastik izleri** — kayan ve patinaj yapan tekerler yola gerçekten iz bırakır.
- **Çarpışma** — binalara, ağaçlara, kayalara ve sokak lambalarına çarpılır.
  Burnuna gelen sıyırma darbesi aracı döndürür; telefon da titrer.
- **Işıklar** — iki ayrı far konisi (kısa huzme aşağı, uzun huzme ileri ve
  uzağa), fren lambası, geri vites lambası, gece yanan sokak lambası havuzları
  ve trafikteki araçların farları.
- **Ses** — kayan lastiğin ciyaklaması, patinajda yükselen devir.
- **Kamera** — hızda, bozuk zeminde ve sert inişlerde titrer; kaput
  kamerasında daha çok.
- **Trafik** — önündeki araç senin için yavaşlar ve stop lambaları yanar.
- **Geri vites** — dururken frene basınca geri vites seçilir; geri viteste
  fren pedalı gaz, gaz pedalı fren olur.

## Garaj

Sekiz aracın hepsi `CarSpec.GARAGE` içindeki sayılarla tanımlı — gövde oranları,
aks başına tutuş, ağırlık dağılımı ve direksiyon kilidi. Yeni bir araç eklemek
için tek yapman gereken o diziye bir satır daha yazmak.

`SAVRULMA` sütunu ön tutuş eksi arka tutuş: ne kadar büyükse arka o kadar erken
bırakır. `KİLİT` maksimum direksiyon açısı — drift araçları çok daha fazlasını
ister, çünkü ters direksiyonda o açıya ihtiyacın var.

| Araç | 0-100 | Son hız | Savrulma | Kilit | Karakter |
|---|---|---|---|---|---|
| DUMAN DRIFT | 5.1 sn | 230 km/s | +2.45 | 53° | Drift için yapılmış: kocaman kilit, erken bırakan arka |
| FIRTINA RS | 4.7 sn | 248 km/s | +2.00 | 50° | Hafif, sinirli, düşünmekle döner |
| KAS MUSCLE | 5.0 sn | 256 km/s | +1.90 | 46° | Uzun kaput, ağır kıç — klasik drift makinesi |
| SIMSEK GT | 3.3 sn | 277 km/s | +0.80 | 40° | Hızlı olacak kadar tutuşlu, oynanacak kadar serbest |
| KLASIK SEDAN | 6.5 sn | 216 km/s | -0.20 | 36° | Aklı başında olan: savrulmaktansa dışarı kaçar |
| KARTAL SUV | 7.4 sn | 209 km/s | -0.10 | 34° | Yüksek, sağlam, kaldırımdan korkmaz, geç döner |
| MINIK HATCH | 9.6 sn | 169 km/s | +0.10 | 38° | Kısa dingil, kavşaklarda çevik |
| YUK PICKUP | 9.6 sn | 184 km/s | +0.60 | 33° | Açık kasa, ağır, arkası eğlenceden çok dert |

## Derleme

Android SDK (platform 35, build-tools 35.0.0) ve JDK 17+ gerekiyor.
`local.properties` içine SDK yolunu yaz:

```
sdk.dir=/senin/android/sdk/yolun
```

Sonra:

```bash
gradle assembleRelease      # dist'teki APK böyle üretildi
gradle assembleDebug
```

Release derlemesi, `keystore.properties` yoksa debug anahtarıyla imzalanır —
yani kutudan çıktığı gibi derlenir ve kurulur. Kendi anahtarınla imzalamak
için depo kökünde şöyle bir dosya oluştur (bu dosya `.gitignore`'da):

```properties
storeFile=/yol/anahtar.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> Not: `dist/` içindeki APK debug anahtarıyla imzalı. Yeni bir sürüm derlersen
> imza değişeceği için üstüne kuramazsın; önce eskisini kaldır. Kalıcı bir
> anahtar oluşturursan bu sorun ortadan kalkar.

## Doğrulama

Bu depoda emülatör gerektirmeyen bir test koşumu var. Oyunun **gerçek kaynak
dosyalarını** masaüstü JVM'de derleyip çalıştırır (`android.opengl.Matrix`
yerine birebir aynı davranan bir kopya konur):

```bash
tools/harness/run.sh            # 221 kontrol
tools/harness/run.sh --preview  # kontroller + docs/*.png görüntülerini yeniden üretir
```

Kontrol ettikleri: arazi yüksekliğinin sürekliliği ve belirleyiciliği, chunk
geometrisinin indeks sınırları içinde kalması, her üçgenin doğru yöne bakması
(arka yüz eleme yanlış sarımlı üçgenleri yutar), araç gövdelerinin tutarlılığı,
her aracın ilan ettiği son hıza ulaşması, frenin geri vitese geçmesi, el
freninin gerçekten kaydırması, toprakta yavaşlaması ve frustum elemesi.

**Sürüş mekaniği testleri**: her aracın altı vitesi de kullanması ve vites
değişiminde devrin düşmesi, devir sınırlayıcısının tutması, yavaşlarken vites
küçültmesi, her aracın 0-100 süresinin inandırıcı bir aralıkta olması, yokuşta
park eden aracın geri kaçması ve el freninin onu tutması, kaldırımdan hızlı
inen aracın havalanıp geri inmesi, süspansiyonun dururken oturması.

**Drift testleri** modelin var olma sebebini ölçüyor: arkası erken bırakan
aracın aynı girdiyle dengeli olandan daha çok kayması, el freninin arkayı
attırması, gazda alınan virajın gaz kesikten daha çok savurması, kaymanın
yönünde direksiyon kırmanın onu toparlaması (ters yöne kırmanın toparlamaması),
tutulan bir driftin hem açısını hem hızının dörtte üçünü koruması, çarpanın
büyüyüp araç düzelince hanene yazılması, dönmenin direksiyon bırakıldıktan
sonra da devam etmesi, ve en kötü savrulmada bile dönme hızının cevap
verilebilir kalması.

Bunlardan biri gerçek bir hatayı ortaya çıkardı: model "hız" derken aracın
burnu yönündeki hızı kullanıyordu. Araç tam yana geldiğinde o sayı sıfırlanıyor,
dolayısıyla hava direnci de yuvarlanma direnci de lastik kuvveti de sıfırlanıyor
ve araç buz üstünde gibi sonsuza kadar yan kayıyordu. Artık her şey aracın
gerçek yer hızını görüyor; test de tam yana dönmüş bir aracın lastikleri
tarafından yavaşladığını doğruluyor.

Ayrıca **direksiyonun doğru yöne çevirmesi**: aracın gittiği yön, "sağ"ın
tanımından (ileri × yukarı çapraz çarpımı) bağımsız olarak, üç farklı başlangıç
açısında ve iki yönde de doğrulanıyor. Bu testin ilk hâli benim yanlış
varsayımımı tekrarladığı için hatayı yakalayamamıştı; şimdi kodun iç
işaret kuralına değil, geometrinin tanımına bakıyor.

**Arayüz testi** ise tek bir parmağı beş farklı ekran oranında (16:9'dan 5:4'e)
ekranın her yerinde gezdirip hiçbir noktanın aynı anda iki kontrolü birden
tetiklemediğini doğruluyor — geliştiricinin kendi telefonunda görünmeyen,
başkasının telefonunda sinir bozucu olan türden bir hata.

**Çarpışma testi** en kritiği: dünya üreticisinin çizdiği duvarlarla çarpışma
kutularının aynı yerde olduğunu doğruluyor. Bunun için bina yerleşimi ile bina
görünümü ayrı rastgele akışlardan besleniyor; böylece çarpışma yürüyüşü hiçbir
renk veya pencere sayısı okumadan mesh'in gezdiği aynı binaları geziyor. Test
iki yönü de kontrol ediyor: bildirilen her kutunun üstünde gerçekten bir bina
var mı, ve araç gövdesi yüksekliğindeki her katı yüzey çarpışma listesinde mi.
Ayrıca bir araç duvara sürülüp içine girmediği ve hız kaybettiği ölçülüyor.

`Preview.java` oyunun kendi gölgelendirici matematiğini taklit eden küçük bir
yazılımsal rasterleştirici; `HudPreview.java` ise gerçek `Hud` kodunu masaüstünde
çalıştırıp ürettiği üçgenleri o sahnenin üzerine çiziyor. Yukarıdaki ekran
görüntüleri bunlarla üretildi — yani arayüz, cihaz olmadan da gerçekten
görülerek tasarlandı. Hepsi geliştirme aracı, APK'ya girmiyorlar.

## Nasıl çalışıyor

```
app/src/main/java/com/ilker/opendrive/
├── MainActivity, GameView, GameRenderer   oyun döngüsü, kamera, ışık
├── gl/      MeshBuilder  prosedürel geometri (prizma, silindir, konik kutu)
│            Mesh, SceneProgram, HudProgram, Frustum, ShaderUtil
├── world/   Terrain      dünyanın tanımı: yükseklik, biyom, yol ağı
│            ChunkBuilder arazi + yol + bina + ağaç ağlarını üretir
│            World        oyuncunun etrafındaki chunk akışı
├── game/    CarSpec, CarMesh, Car, Traffic, CarModels
├── ui/      Hud, PixelFont
└── audio/   EngineSound  AudioTrack üzerine sentezlenen motor sesi
```

Dünyanın tamamı koordinatların saf bir fonksiyonu: `Terrain.height(x, z)`
her zaman aynı cevabı verir, bu yüzden chunk'lar herhangi bir sırada, herhangi
bir iş parçacığında üretilebilir ve birbirleriyle her zaman uyumlu olur. Chunk
üretimi iki arka plan iş parçacığında, GPU'ya yükleme ise kare başına en fazla
iki chunk ile sınırlı — böylece sürüş sırasında takılma olmuyor.

Sahnenin tamamı tek bir gölgelendiriciyle çiziliyor: yönlü güneş ışığı, gökyüzü
dolgusu, üstel sis, pencereler ve lambalar için ışıma kanalı, gece için far
konisi.

---

`index.html` bu depodaki ayrı bir proje (İlker web uygulaması) ve oyunla
ilgisi yoktur.
