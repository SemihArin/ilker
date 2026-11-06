(function () {
  'use strict';

  var ayarlar = null;
  var gecmis = [];
  var gecmisHaritasi = {};

  // ---------------- Yerel depo (localStorage) ----------------
  // Geçmiş, son aramalar ve ayarlar yalnızca bu tarayıcıda tutulur; sunucuya gönderilmez.

  var ANAHTAR_GECMIS = 'arama.gecmis';
  var ANAHTAR_AYARLAR = 'arama.ayarlar';
  var ANAHTAR_ARAMALAR = 'arama.sonAramalar';
  var ANAHTAR_KAYDEDILENLER = 'arama.kaydedilenler';
  var GECMIS_SINIRI = 200;
  var KAYDEDILEN_SINIRI = 500;
  var ARAMA_SINIRI = 10;
  var VIDEO_KIMLIGI = /^[A-Za-z0-9_-]{11}$/;
  var LINK_GIBI = /^(https?:\/\/|www\.|m\.youtube|youtu)|^[\w-]{11}$/i;
  var ORNEK_ARAMALAR = ['Lo-fi müzik', 'Belgesel', 'Yemek tarifi', 'Komik kediler', 'Gitar dersi'];
  var VARSAYILAN_AYARLAR = {
    tema: 'sistem', // 'sistem' | 'acik' | 'koyu'
    otomatikOynat: false,
    kaldigimYerdenDevam: true,
    siradakiniOynat: true,
    gecmisiKaydet: true,
    davetleriKabulEt: true,
    kullaniciAdi: 'Misafir',
  };

  var depolamaUyarildi = false;
  function yerelOku(anahtar) {
    try {
      var ham = localStorage.getItem(anahtar);
      return ham ? JSON.parse(ham) : null;
    } catch (e) {
      return null;
    }
  }
  function yerelYaz(anahtar, deger) {
    try {
      localStorage.setItem(anahtar, JSON.stringify(deger));
      return true;
    } catch (e) {
      if (!depolamaUyarildi) {
        depolamaUyarildi = true;
        bildir('Tarayıcı depolaması kullanılamıyor; değişiklikler kaydedilmeyecek');
      }
      return false;
    }
  }

  function ayarlariOku() {
    var ham = yerelOku(ANAHTAR_AYARLAR) || {};
    var a = {};
    Object.keys(VARSAYILAN_AYARLAR).forEach(function (k) {
      var v = ham[k];
      var varsayilan = VARSAYILAN_AYARLAR[k];
      a[k] = typeof v === typeof varsayilan ? v : varsayilan;
    });
    if (['sistem', 'acik', 'koyu'].indexOf(a.tema) < 0) a.tema = 'sistem';
    a.kullaniciAdi = String(a.kullaniciAdi).trim().slice(0, 32) || VARSAYILAN_AYARLAR.kullaniciAdi;
    return a;
  }

  function gecmisiOku() {
    var ham = yerelOku(ANAHTAR_GECMIS);
    return (Array.isArray(ham) ? ham : [])
      .filter(function (k) {
        return k && typeof k.id === 'string' && VIDEO_KIMLIGI.test(k.id) && typeof k.izlenme === 'string' && typeof k.baslik === 'string';
      })
      .sort(function (a, b) { return b.izlenme < a.izlenme ? -1 : b.izlenme > a.izlenme ? 1 : 0; });
  }

  function gecmisiYaz(liste) {
    return yerelYaz(ANAHTAR_GECMIS, liste.slice(0, GECMIS_SINIRI));
  }

  function videoGecerliMi(k) {
    return k && typeof k.id === 'string' && VIDEO_KIMLIGI.test(k.id) && typeof k.baslik === 'string';
  }

  // Daha sonra izle listesi (en yeni en üstte).
  function kaydedilenleriOku() {
    var ham = yerelOku(ANAHTAR_KAYDEDILENLER);
    return (Array.isArray(ham) ? ham : []).filter(videoGecerliMi);
  }
  function kaydedilenleriYaz(liste) {
    return yerelYaz(ANAHTAR_KAYDEDILENLER, liste.slice(0, KAYDEDILEN_SINIRI));
  }

  function aramalariOku() {
    var ham = yerelOku(ANAHTAR_ARAMALAR);
    return (Array.isArray(ham) ? ham : []).filter(function (q) { return typeof q === 'string' && q.trim(); }).slice(0, ARAMA_SINIRI);
  }

  function aramayiHatirla(q) {
    if (!q || LINK_GIBI.test(q)) return;
    var kucuk = kucult(q);
    var liste = aramalariOku().filter(function (x) { return kucult(x) !== kucuk; });
    liste.unshift(q);
    yerelYaz(ANAHTAR_ARAMALAR, liste.slice(0, ARAMA_SINIRI));
  }

  // Eski sürümün tek "son arama" kaydını listeye taşı.
  (function () {
    try {
      var eski = localStorage.getItem('arama.sonArama');
      if (eski !== null) {
        if (!aramalariOku().length && eski.trim()) yerelYaz(ANAHTAR_ARAMALAR, [eski.trim()]);
        localStorage.removeItem('arama.sonArama');
      }
    } catch (e) {}
  })();

  // ---------------- Yardımcılar ----------------

  // Sunucuya yalnızca arama istekleri gider.
  function api(yol) {
    return fetch('/api' + yol).then(function (yanit) {
      return yanit.json().catch(function () { return {}; }).then(function (veri) {
        if (!yanit.ok) {
          var hata = new Error(veri.hata || 'İstek başarısız (' + yanit.status + ')');
          hata.kod = veri.kod;
          hata.durum = yanit.status;
          throw hata;
        }
        return veri;
      });
    }, function () {
      var hata = new Error('Sunucuya ulaşılamadı. İnternet bağlantını kontrol et.');
      hata.kod = 'BAGLANTI';
      throw hata;
    });
  }

  function el(etiket, sinif, metin) {
    var e = document.createElement(etiket);
    if (sinif) e.className = sinif;
    if (metin !== undefined) e.textContent = metin;
    return e;
  }

  function $(id) { return document.getElementById(id); }

  function kucult(s) { return String(s).toLocaleLowerCase('tr'); }

  var IKONLAR = {
    saat: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    ara: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    oynat: '<path d="M7 4.5v15l13-7.5z"/>',
    anahtar: '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M17 6l3 3"/>',
    uyari: '<path d="M12 3l10 18H2z"/><path d="M12 10v4M12 17.5v.5"/>',
    bos: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5M8.5 11h5"/>',
    kaydet: '<path d="M6 4h12v17l-6-4-6 4z"/>',
    kapat: '<path d="M6 6l12 12M18 6L6 18"/>',
    paylas: '<path d="M12 3v12M7 8l5-5 5 5M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5"/>',
  };
  function ikon(ad) {
    var span = el('span');
    span.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + IKONLAR[ad] + '</svg>';
    return span.firstChild;
  }

  // Bildirim; isteğe bağlı bir eylem düğmesiyle (ör. "Geri al").
  var toastZamani;
  function bildir(mesaj, eylemAdi, eylem, sure) {
    var t = $('toast');
    t.textContent = '';
    t.appendChild(el('span', '', mesaj));
    if (eylemAdi) {
      var b = el('button', '', eylemAdi);
      b.type = 'button';
      b.addEventListener('click', function () {
        t.classList.remove('show');
        eylem();
      });
      t.appendChild(b);
    }
    t.classList.add('show');
    clearTimeout(toastZamani);
    toastZamani = setTimeout(function () { t.classList.remove('show'); }, sure || (eylemAdi ? 5000 : 2600));
  }
  function bildirimiKapat() {
    clearTimeout(toastZamani);
    $('toast').classList.remove('show');
  }

  function goreliZaman(iso) {
    var fark = Math.max(0, Date.now() - new Date(iso).getTime());
    var dk = Math.floor(fark / 60000);
    if (dk < 1) return 'az önce';
    if (dk < 60) return dk + ' dakika önce';
    var sa = Math.floor(dk / 60);
    if (sa < 24) return sa + ' saat önce';
    var gun = Math.floor(sa / 24);
    if (gun < 7) return gun + ' gün önce';
    if (gun < 30) return Math.floor(gun / 7) + ' hafta önce';
    if (gun < 365) return Math.floor(gun / 30) + ' ay önce';
    return Math.floor(gun / 365) + ' yıl önce';
  }

  function grupAdi(iso) {
    var d = new Date(iso);
    var bugun = new Date();
    bugun.setHours(0, 0, 0, 0);
    var gunFarki = Math.round((bugun - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
    if (gunFarki <= 0) return 'Bugün';
    if (gunFarki === 1) return 'Dün';
    if (gunFarki < 7) return 'Bu hafta';
    return 'Daha eski';
  }

  var sayiBicimi;
  try { sayiBicimi = new Intl.NumberFormat('tr-TR', { notation: 'compact', maximumFractionDigits: 1 }); }
  catch (e) { sayiBicimi = new Intl.NumberFormat('tr-TR'); }

  function sureMetni(saniye) {
    saniye = Math.floor(saniye);
    var sa = Math.floor(saniye / 3600);
    var dk = Math.floor((saniye % 3600) / 60);
    var sn = saniye % 60;
    var iki = function (n) { return (n < 10 ? '0' : '') + n; };
    return sa ? sa + ':' + iki(dk) + ':' + iki(sn) : dk + ':' + iki(sn);
  }

  function sureSaniye(metin) {
    if (!metin) return 0;
    return String(metin).split(':').reduce(function (t, p) { return t * 60 + (Number(p) || 0); }, 0);
  }

  // Kart altı bilgisi: "12 dk kaldı" / "İzlendi" / "Yeni".
  function kalanMetni(k) {
    if (k.ilerleme >= 95) return 'İzlendi';
    var toplam = sureSaniye(k.sure);
    if (!toplam || !k.konum) return goreliZaman(k.izlenme);
    var kalan = Math.max(0, toplam - k.konum);
    if (kalan < 60) return '1 dakikadan az kaldı';
    var dk = Math.round(kalan / 60);
    return dk >= 60 ? Math.floor(dk / 60) + ' sa ' + (dk % 60) + ' dk kaldı' : dk + ' dk kaldı';
  }

  function ilerlemeCubugu(sinif, yuzde) {
    var bar = el('div', sinif);
    var ic = el('span');
    ic.style.setProperty('--p', yuzde + '%');
    bar.appendChild(ic);
    return bar;
  }

  function resim(url, alt) {
    var img = el('img');
    img.loading = 'lazy';
    img.decoding = 'async';
    img.alt = alt || '';
    img.referrerPolicy = 'no-referrer';
    img.src = url;
    img.onerror = function () { img.remove(); };
    return img;
  }

  function kucukResimAdresi(video) {
    return video.kucukResim || 'https://i.ytimg.com/vi/' + video.id + '/mqdefault.jpg';
  }

  function devamGoster() {
    return !ayarlar || ayarlar.kaldigimYerdenDevam;
  }

  function yaziyorMu(hedef) {
    return hedef && (hedef.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(hedef.tagName));
  }

  // ---------------- Gezinme (adres çubuğu ve geri tuşu ile uyumlu) ----------------
  // Adresler: /  ·  /?q=arama  ·  /gecmis  ·  /ayarlar

  var linkler = document.querySelectorAll('.menu a[data-page]');
  var sayfalar = document.querySelectorAll('.page');
  var gecerliSayfa = 'home';

  function sayfaGoster(ad) {
    var degisti = ad !== gecerliSayfa;
    gecerliSayfa = ad;
    sayfalar.forEach(function (p) { p.hidden = p.id !== 'p-' + ad; });
    linkler.forEach(function (a) {
      var aktifMi = a.dataset.page === ad;
      a.classList.toggle('active', aktifMi);
      if (aktifMi) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
    if (degisti) window.scrollTo(0, 0);
  }

  function adresiUygula(linkiAc) {
    var yol = location.pathname.replace(/\/+$/, '');
    if (yol === '/gecmis') return sayfaGoster('gecmis');
    if (yol === '/kaydedilenler') return sayfaGoster('kaydedilenler');
    if (yol === '/ayarlar') return sayfaGoster('ayarlar');
    sayfaGoster('home');
    var q = (new URLSearchParams(location.search).get('q') || '').trim();
    aramaKutusu.value = q;
    temizleDugmesi();
    if (q !== arama.q) aramaYap(q, false, linkiAc);
  }

  function git(adres, linkiAc) {
    if (adres !== location.pathname + location.search) history.pushState(null, '', adres);
    adresiUygula(linkiAc);
  }

  function anaSayfaAdresi() {
    return arama.q ? '/?q=' + encodeURIComponent(arama.q) : '/';
  }

  window.addEventListener('popstate', function () { adresiUygula(false); });

  linkler.forEach(function (a) {
    a.addEventListener('click', function (e) {
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return; // yeni sekmede açmaya izin ver
      e.preventDefault();
      var ad = a.dataset.page;
      if (ad === 'home' && gecerliSayfa === 'home') {
        window.scrollTo({ top: 0, behavior: 'smooth' });
        return;
      }
      git(ad === 'home' ? anaSayfaAdresi() : '/' + ad);
    });
  });
  function menuAdresleriniGuncelle() {
    linkler.forEach(function (a) {
      a.href = a.dataset.page === 'home' ? anaSayfaAdresi() : '/' + a.dataset.page;
    });
  }
  document.querySelector('.see-all-right').addEventListener('click', function () { git('/gecmis'); });

  // ---------------- Video satırı ----------------

  function videoSatiri(video, secenek) {
    secenek = secenek || {};
    var kayit = gecmisHaritasi[video.id];
    var satir = el('div', 'result');
    satir.dataset.id = video.id;
    satir.tabIndex = 0;
    satir.setAttribute('role', 'button');
    satir.setAttribute('aria-label', video.baslik + (video.kanal ? ', ' + video.kanal : ''));
    if (aktif && aktif.id === video.id) satir.classList.add('playing');

    var thumb = el('div', 'thumb');
    thumb.appendChild(resim(kucukResimAdresi(video)));
    if (video.canli) thumb.appendChild(el('span', 'duration live', 'CANLI'));
    else if (video.sure || (kayit && kayit.sure)) thumb.appendChild(el('span', 'duration', video.sure || kayit.sure));
    if (devamGoster() && kayit && kayit.ilerleme > 0) {
      if (kayit.ilerleme >= 95) thumb.appendChild(el('span', 'badge', 'İZLENDİ'));
      thumb.appendChild(ilerlemeCubugu('thumb-bar', kayit.ilerleme));
    }
    satir.appendChild(thumb);

    var bilgi = el('div');
    bilgi.appendChild(el('h3', 'result-title', video.baslik));
    bilgi.appendChild(el('div', 'result-channel', video.kanal || ''));
    var meta = [];
    if (secenek.gecmis) {
      meta.push(goreliZaman(video.izlenme));
      if (devamGoster() && video.ilerleme > 0 && video.ilerleme < 95 && sureSaniye(video.sure)) meta.push(kalanMetni(video));
    } else {
      if (typeof video.izlenmeSayisi === 'number') meta.push(sayiBicimi.format(video.izlenmeSayisi) + ' görüntüleme');
      if (video.yayinTarihi) meta.push(goreliZaman(video.yayinTarihi));
    }
    if (meta.length) bilgi.appendChild(el('div', 'result-meta', meta.join(' · ')));
    satir.appendChild(bilgi);

    var eylemler = el('div', 'result-actions');
    if (!secenek.kaydetYok) {
      var kayitli = kayitliMi(video.id);
      var kaydet = el('button', 'row-btn save-btn' + (kayitli ? ' on' : ''));
      kaydet.type = 'button';
      kaydet.title = kayitli ? 'Kaydedilenlerden çıkar' : 'Daha sonra izle';
      kaydet.setAttribute('aria-label', kaydet.title + ': ' + video.baslik);
      kaydet.setAttribute('aria-pressed', kayitli ? 'true' : 'false');
      kaydet.appendChild(ikon('kaydet'));
      eylemler.appendChild(kaydet);
    }
    if (secenek.kaldir) {
      var sil = el('button', 'row-btn remove-btn');
      sil.type = 'button';
      sil.title = secenek.kaldir;
      sil.setAttribute('aria-label', secenek.kaldir + ': ' + video.baslik);
      sil.appendChild(ikon('kapat'));
      eylemler.appendChild(sil);
    }
    satir.appendChild(eylemler);
    return satir;
  }

  // Boş durum / hata / karşılama kutusu.
  function karsilama(secenek) {
    var kutu = el('div', 'hero' + (secenek.hata ? ' hata' : ''));
    var simge = el('div', 'hero-icon');
    simge.appendChild(ikon(secenek.ikon));
    kutu.appendChild(simge);
    kutu.appendChild(el('h2', '', secenek.baslik));
    if (secenek.metin) kutu.appendChild(el('p', '', secenek.metin));
    if (secenek.cipler && secenek.cipler.length) {
      var c = el('div', 'chips');
      secenek.cipler.forEach(function (q) {
        var b = el('button', 'chip', q);
        b.type = 'button';
        b.addEventListener('click', function () { git('/?q=' + encodeURIComponent(q)); });
        c.appendChild(b);
      });
      kutu.appendChild(c);
    }
    if (secenek.dugme) {
      var d = el('button', 'btn btn-ghost', secenek.dugme);
      d.type = 'button';
      d.addEventListener('click', secenek.eylem);
      kutu.appendChild(d);
    }
    return kutu;
  }

  // ---------------- Kaydedilenler (daha sonra izle) ----------------

  var kaydedilenler = [];
  var kaydedilenHaritasi = {};

  function kayitliMi(id) { return !!kaydedilenHaritasi[id]; }

  function kaydedilenleriYukle() {
    kaydedilenler = kaydedilenleriOku();
    kaydedilenHaritasi = {};
    kaydedilenler.forEach(function (k) { kaydedilenHaritasi[k.id] = k; });
    kaydedilenSayfasiniCiz();
    kayitIsaretleriniGuncelle();
  }

  function kaydedilenSayfasiniCiz() {
    var kap = $('kaydedilen-listesi');
    kap.textContent = '';
    $('kaydedilen-eylemler').hidden = !kaydedilenler.length;
    $('kaydedilen-sayisi').textContent = kaydedilenler.length ? kaydedilenler.length + ' video' : '';
    if (!kaydedilenler.length) {
      kap.appendChild(karsilama({
        ikon: 'kaydet',
        baslik: 'Kaydedilen video yok',
        metin: 'Bir videonun yanındaki kaydet simgesine dokunarak onu daha sonra izlemek üzere buraya ekleyebilirsin.',
        dugme: 'Video ara',
        eylem: function () { git(anaSayfaAdresi()); aramaKutusu.focus(); },
      }));
      return;
    }
    kaydedilenler.forEach(function (k) {
      kap.appendChild(videoSatiri(k, { kaldir: 'Kaydedilenlerden çıkar', kaydetYok: true }));
    });
  }

  // Sayfadaki tüm kaydet düğmelerinin durumunu günceller (yeniden çizmeden).
  function kayitIsaretleriniGuncelle() {
    document.querySelectorAll('.result[data-id] .save-btn').forEach(function (b) {
      var satir = b.closest('.result');
      var on = kayitliMi(satir.dataset.id);
      b.classList.toggle('on', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.title = on ? 'Kaydedilenlerden çıkar' : 'Daha sonra izle';
    });
    var simdi = $('simdi-kaydet');
    var on = !!(aktif && kayitliMi(aktif.id));
    simdi.classList.toggle('on', on);
    simdi.setAttribute('aria-pressed', on ? 'true' : 'false');
    simdi.title = on ? 'Kaydedilenlerden çıkar' : 'Daha sonra izle';
    menuSayaciniGuncelle();
  }

  function menuSayaciniGuncelle() {
    var a = document.querySelector('.menu a[data-page="kaydedilenler"]');
    a.setAttribute('aria-label', 'Kaydedilenler' + (kaydedilenler.length ? ' (' + kaydedilenler.length + ')' : ''));
  }

  function videoOzeti(v) {
    var kayit = gecmisHaritasi[v.id] || {};
    return {
      id: v.id,
      baslik: v.baslik,
      kanal: v.kanal || kayit.kanal || '',
      sure: v.sure || kayit.sure || '',
      kucukResim: v.kucukResim || kayit.kucukResim || '',
    };
  }

  function kaydetDegistir(video) {
    var liste = kaydedilenleriOku();
    var varMi = liste.some(function (k) { return k.id === video.id; });
    if (varMi) {
      var eski = liste;
      kaydedilenleriYaz(liste.filter(function (k) { return k.id !== video.id; }));
      kaydedilenleriYukle();
      bildir('Kaydedilenlerden çıkarıldı', 'Geri al', function () {
        kaydedilenleriYaz(eski);
        kaydedilenleriYukle();
      });
    } else {
      var yeni = videoOzeti(video);
      yeni.eklenme = new Date().toISOString();
      liste.unshift(yeni);
      kaydedilenleriYaz(liste);
      kaydedilenleriYukle();
      bildir('Daha sonra izlemek için kaydedildi', 'Göster', function () { git('/kaydedilenler'); });
    }
  }

  function kaydedilenleriTemizle() {
    var eski = kaydedilenleriOku();
    if (!eski.length) return;
    kaydedilenleriYaz([]);
    kaydedilenleriYukle();
    bildir(eski.length + ' video kaydedilenlerden çıkarıldı', 'Geri al', function () {
      kaydedilenleriYaz(eski);
      kaydedilenleriYukle();
    });
  }

  // ---------------- Geçmiş ----------------

  var filtreKutusu = $('gecmis-filtre');

  function gecmisSayfasiniCiz() {
    var kap = $('gecmis-listesi');
    kap.textContent = '';
    $('gecmisi-temizle').hidden = gecmis.length === 0;
    $('gecmis-filtre-alani').hidden = gecmis.length < 5;
    if (!gecmis.length) {
      var kapali = ayarlar && !ayarlar.gecmisiKaydet;
      kap.appendChild(karsilama({
        ikon: 'saat',
        baslik: kapali ? 'Geçmiş kaydı kapalı' : 'Henüz izlenen video yok',
        metin: kapali ? 'Ayarlar’dan “Geçmişi kaydet”i açarsan izlediğin videolar burada görünür.' : 'İzlediğin videolar burada, kaldığın yerle birlikte görünecek.',
        dugme: kapali ? 'Ayarlara git' : 'Video ara',
        eylem: function () {
          if (kapali) git('/ayarlar');
          else { git(anaSayfaAdresi()); aramaKutusu.focus(); }
        },
      }));
      return;
    }
    var filtre = kucult(filtreKutusu.value.trim());
    var liste = filtre
      ? gecmis.filter(function (k) { return kucult(k.baslik + ' ' + (k.kanal || '')).indexOf(filtre) >= 0; })
      : gecmis;
    if (!liste.length) {
      kap.appendChild(karsilama({ ikon: 'bos', baslik: 'Eşleşen video yok', metin: '“' + filtreKutusu.value.trim() + '” geçmişinde bulunamadı.' }));
      return;
    }
    var sonGrup = null;
    var grup;
    liste.forEach(function (k) {
      var ad = grupAdi(k.izlenme);
      if (ad !== sonGrup) {
        kap.appendChild(el('h2', 'section-title', ad));
        grup = el('div', 'list');
        kap.appendChild(grup);
        sonGrup = ad;
      }
      grup.appendChild(videoSatiri(k, { gecmis: true, kaldir: 'Geçmişten kaldır' }));
    });
  }
  filtreKutusu.addEventListener('input', gecmisSayfasiniCiz);

  function kartlariCiz() {
    var kap = $('son-izlenenler');
    kap.textContent = '';
    gecmis.slice(0, 12).forEach(function (k) {
      var kart = el('div', 'card');
      kart.dataset.id = k.id;
      kart.tabIndex = 0;
      kart.setAttribute('role', 'button');
      kart.setAttribute('aria-label', k.baslik);
      kart.title = k.baslik;
      var img = el('div', 'card-img');
      img.appendChild(resim(kucukResimAdresi(k)));
      if (k.sure) img.appendChild(el('span', 'duration', k.sure));
      kart.appendChild(img);
      var govde = el('div', 'card-body');
      govde.appendChild(el('h3', 'card-title', k.baslik));
      if (devamGoster()) govde.appendChild(ilerlemeCubugu('bar', k.ilerleme || 0));
      govde.appendChild(el('div', 'card-meta', devamGoster() ? kalanMetni(k) : goreliZaman(k.izlenme)));
      kart.appendChild(govde);
      kap.appendChild(kart);
    });
    kartOklariniGuncelle();
  }

  // Masaüstünde kart şeridi için kaydırma okları.
  var kartlar = $('son-izlenenler');
  var oncekiOk = document.querySelector('.cards-nav.prev');
  var sonrakiOk = document.querySelector('.cards-nav.next');
  function kartOklariniGuncelle() {
    oncekiOk.disabled = kartlar.scrollLeft <= 4;
    sonrakiOk.disabled = kartlar.scrollLeft + kartlar.clientWidth >= kartlar.scrollWidth - 4;
  }
  oncekiOk.addEventListener('click', function () { kartlar.scrollBy({ left: -kartlar.clientWidth * 0.8 }); });
  sonrakiOk.addEventListener('click', function () { kartlar.scrollBy({ left: kartlar.clientWidth * 0.8 }); });
  kartlar.addEventListener('scroll', kartOklariniGuncelle, { passive: true });
  window.addEventListener('resize', kartOklariniGuncelle);

  function gecmisiCiz() {
    gecmisSayfasiniCiz();
    kartlariCiz();
    anaGorunumuGuncelle();
  }

  function gecmisiAyarla(liste) {
    gecmis = liste;
    gecmisHaritasi = {};
    gecmis.forEach(function (k) { gecmisHaritasi[k.id] = k; });
  }

  function gecmisiYukle() {
    gecmisiAyarla(gecmisiOku());
    gecmisiCiz();
  }

  function gecmistenKaldir(id) {
    var eskiListe = gecmisiOku();
    var kalan = eskiListe.filter(function (k) { return k.id !== id; });
    if (kalan.length === eskiListe.length) return;
    gecmisiYaz(kalan);
    gecmisiYukle();
    bildir('Geçmişten kaldırıldı', 'Geri al', function () {
      gecmisiYaz(eskiListe);
      gecmisiYukle();
    });
  }

  function gecmisiTemizle() {
    var eskiListe = gecmisiOku();
    if (!eskiListe.length) return bildir('Geçmiş zaten boş');
    gecmisiYaz([]);
    filtreKutusu.value = '';
    gecmisiYukle();
    bildir(eskiListe.length + ' video geçmişten silindi', 'Geri al', function () {
      gecmisiYaz(eskiListe);
      gecmisiYukle();
    });
  }

  // ---------------- Arama ----------------

  var arama = { q: '', sonuclar: [], sonrakiSayfa: null, yukleniyor: false, istek: 0, hata: null };
  var sonucHaritasi = {};
  var aramaKutusu = $('arama');

  function iskeletCiz(adet) {
    var kap = $('sonuclar');
    kap.textContent = '';
    for (var i = 0; i < adet; i++) {
      var s = el('div', 'result skeleton');
      s.setAttribute('aria-hidden', 'true');
      s.appendChild(el('div', 'thumb'));
      var b = el('div');
      b.style.flex = '1';
      var l1 = el('div', 'sk-line'); l1.style.width = '90%';
      var l2 = el('div', 'sk-line'); l2.style.width = '50%';
      b.appendChild(l1); b.appendChild(l2);
      s.appendChild(b);
      kap.appendChild(s);
    }
  }

  function sonuclariCiz() {
    var kap = $('sonuclar');
    if (arama.yukleniyor && !arama.sonuclar.length) return;
    kap.textContent = '';
    var h = arama.hata;
    if (h) {
      if (h.kod === 'ANAHTAR_YOK') {
        kap.appendChild(karsilama({ ikon: 'anahtar', baslik: 'Yazarak arama kapalı', metin: 'Sunucuda YouTube API anahtarı tanımlı değil. Yine de bir YouTube linki yapıştırarak video açabilirsin.' }));
      } else if (h.kod === 'KOTA_DOLDU') {
        kap.appendChild(karsilama({ ikon: 'uyari', hata: true, baslik: 'Günlük arama kotası doldu', metin: 'YouTube arama kotası yarın yenilenecek. Bu sürede YouTube linki yapıştırarak video açabilirsin.' }));
      } else {
        kap.appendChild(karsilama({
          ikon: 'uyari', hata: true, baslik: 'Bir sorun oluştu', metin: h.message,
          dugme: 'Tekrar dene', eylem: function () { var q = arama.q; arama.q = null; aramaYap(q); },
        }));
      }
    } else if (!arama.q) {
      var son = aramalariOku().slice(0, 5);
      kap.appendChild(karsilama({
        ikon: 'oynat',
        baslik: gecmis.length ? 'Ne izlemek istersin?' : 'Hoş geldin!',
        metin: 'Video ara ya da bir YouTube linki yapıştır. ' + (son.length ? 'Son aramaların:' : 'Şunlardan biriyle başlayabilirsin:'),
        cipler: son.length ? son : ORNEK_ARAMALAR,
      }));
    } else if (!arama.sonuclar.length) {
      kap.appendChild(karsilama({ ikon: 'bos', baslik: 'Sonuç bulunamadı', metin: '“' + arama.q + '” için video bulunamadı. Farklı kelimelerle aramayı dene.' }));
    }
    arama.sonuclar.forEach(function (v) { kap.appendChild(videoSatiri(v)); });

    var baslik = $('sonuc-basligi');
    baslik.hidden = !arama.q || !!h || !arama.sonuclar.length;
    baslik.textContent = LINK_GIBI.test(arama.q || '') ? 'Bağlantıdaki video' : '“' + arama.q + '” için sonuçlar';

    var daha = $('daha-fazla');
    daha.hidden = !arama.sonrakiSayfa;
    daha.disabled = arama.yukleniyor;
    daha.textContent = arama.yukleniyor ? 'Yükleniyor…' : 'Daha fazla göster';
  }

  // linkiAc: arama bir YouTube linkiyse videoyu doğrudan aç.
  function aramaYap(q, devam, linkiAc) {
    q = (q || '').trim();
    var no = ++arama.istek;
    if (!devam) {
      arama.q = q;
      arama.sonuclar = [];
      arama.sonrakiSayfa = null;
      arama.hata = null;
      arama.yukleniyor = false;
      sonucHaritasi = {};
      menuAdresleriniGuncelle();
      anaGorunumuGuncelle();
      if (!q) { sonuclariCiz(); return Promise.resolve(); }
      aramayiHatirla(q);
      iskeletCiz(6);
    }
    arama.yukleniyor = true;
    if (devam) sonuclariCiz();

    var yol = '/ara?q=' + encodeURIComponent(q) + (devam && arama.sonrakiSayfa ? '&sayfa=' + encodeURIComponent(arama.sonrakiSayfa) : '');
    return api(yol)
      .then(function (yanit) {
        if (no !== arama.istek) return;
        yanit.sonuclar.forEach(function (v) {
          if (sonucHaritasi[v.id]) return; // sayfalar arasında tekrar edenleri atla
          sonucHaritasi[v.id] = v;
          arama.sonuclar.push(v);
        });
        arama.sonrakiSayfa = yanit.sonrakiSayfa;
        if (linkiAc && !devam && yanit.sonuclar.length === 1 && !yanit.sonrakiSayfa && LINK_GIBI.test(q)) {
          videoAc(yanit.sonuclar[0], { kullanici: true });
        }
      })
      .catch(function (err) {
        if (no !== arama.istek) return;
        if (devam) bildir(err.message);
        else arama.hata = err;
      })
      .then(function () {
        if (no !== arama.istek) return;
        arama.yukleniyor = false;
        sonuclariCiz();
      });
  }

  // Ana sayfadaki bölümlerin görünürlüğü.
  function anaGorunumuGuncelle() {
    $('devam-bolumu').hidden = !!arama.q || !gecmis.length;
    $('oynatici-alani').hidden = !aktif;
    $('ana-icerik').classList.toggle('solo', !aktif);
    if (!arama.yukleniyor) sonuclariCiz();
    oynayanIsaretle();
    kartOklariniGuncelle();
  }

  // ---- Arama kutusu: gönderme, temizleme, son aramalar önerisi ----

  var oneriKutusu = $('oneriler');
  var oneriler = [];
  var oneriSecili = -1;

  function temizleDugmesi() {
    $('arama-temizle').hidden = !aramaKutusu.value;
  }

  function onerileriKapat() {
    oneriKutusu.hidden = true;
    oneriSecili = -1;
    aramaKutusu.setAttribute('aria-expanded', 'false');
    aramaKutusu.removeAttribute('aria-activedescendant');
  }

  function onerileriGoster() {
    var deger = kucult(aramaKutusu.value.trim());
    oneriler = aramalariOku().filter(function (q) {
      var k = kucult(q);
      return k !== deger && (!deger || k.indexOf(deger) >= 0);
    }).slice(0, 6);
    oneriSecili = -1;
    oneriKutusu.textContent = '';
    if (!oneriler.length || document.activeElement !== aramaKutusu) return onerileriKapat();

    var bas = el('div', 'suggest-head');
    bas.appendChild(el('span', '', 'Son aramalar'));
    var hepsi = el('button', 'link', 'Tümünü temizle');
    hepsi.type = 'button';
    hepsi.addEventListener('mousedown', function (e) { e.preventDefault(); });
    hepsi.addEventListener('click', function () {
      yerelYaz(ANAHTAR_ARAMALAR, []);
      onerileriKapat();
      if (!arama.q) sonuclariCiz();
    });
    bas.appendChild(hepsi);
    oneriKutusu.appendChild(bas);

    oneriler.forEach(function (q, i) {
      var satir = el('div', 'suggest-item');
      satir.id = 'oneri-' + i;
      satir.setAttribute('role', 'option');
      satir.appendChild(ikon('saat'));
      satir.appendChild(el('span', '', q));
      var sil = el('button', 'remove-mini', '×');
      sil.type = 'button';
      sil.setAttribute('aria-label', 'Son aramalardan kaldır: ' + q);
      sil.tabIndex = -1;
      satir.appendChild(sil);
      // mousedown: kutunun odağı kaybetmemesi için
      satir.addEventListener('mousedown', function (e) { e.preventDefault(); });
      satir.addEventListener('click', function (e) {
        if (e.target.closest('.remove-mini')) {
          yerelYaz(ANAHTAR_ARAMALAR, aramalariOku().filter(function (x) { return x !== q; }));
          onerileriGoster();
          if (!arama.q) sonuclariCiz();
          return;
        }
        aramaKutusu.value = q;
        aramayiGonder();
      });
      oneriKutusu.appendChild(satir);
    });
    oneriKutusu.hidden = false;
    aramaKutusu.setAttribute('aria-expanded', 'true');
  }

  function oneriSec(yon) {
    if (oneriKutusu.hidden || !oneriler.length) return false;
    // -1 = hiçbiri seçili değil (kutudaki metin); listenin iki ucunda ona döner.
    var sonraki = oneriSecili + yon;
    if (sonraki >= oneriler.length) sonraki = -1;
    else if (sonraki < -1) sonraki = oneriler.length - 1;
    oneriSecili = sonraki;
    oneriKutusu.querySelectorAll('.suggest-item').forEach(function (s, i) {
      s.classList.toggle('active', i === oneriSecili);
      s.setAttribute('aria-selected', i === oneriSecili ? 'true' : 'false');
    });
    if (oneriSecili >= 0) aramaKutusu.setAttribute('aria-activedescendant', 'oneri-' + oneriSecili);
    else aramaKutusu.removeAttribute('aria-activedescendant');
    return true;
  }

  function aramayiGonder() {
    var q = aramaKutusu.value.trim();
    onerileriKapat();
    aramaKutusu.blur(); // mobilde klavyeyi kapat
    if (!q) return git('/');
    if (q === arama.q && !arama.hata) {
      // Aynı arama: sadece sonuçlara dön.
      if (gecerliSayfa !== 'home') git(anaSayfaAdresi());
      $('ana-icerik').scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    if (q === arama.q) arama.q = null; // hatalı aramayı yeniden dene
    git('/?q=' + encodeURIComponent(q), true);
  }

  $('arama-formu').addEventListener('submit', function (e) {
    e.preventDefault();
    if (oneriSecili >= 0) aramaKutusu.value = oneriler[oneriSecili];
    aramayiGonder();
  });
  aramaKutusu.addEventListener('focus', onerileriGoster);
  aramaKutusu.addEventListener('blur', function () { setTimeout(onerileriKapat, 0); });
  aramaKutusu.addEventListener('input', function () {
    temizleDugmesi();
    onerileriGoster();
  });
  aramaKutusu.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowDown' && oneriSec(1)) e.preventDefault();
    else if (e.key === 'ArrowUp' && oneriSec(-1)) e.preventDefault();
    else if (e.key === 'Escape') {
      if (!oneriKutusu.hidden) onerileriKapat();
      else aramaKutusu.blur();
    }
  });
  $('arama-temizle').addEventListener('click', function () {
    aramaKutusu.value = '';
    temizleDugmesi();
    aramaKutusu.focus();
    if (arama.q) git('/');
  });
  $('daha-fazla').addEventListener('click', function () {
    if (!arama.yukleniyor && arama.sonrakiSayfa) aramaYap(arama.q, true);
  });

  // ---------------- Oynatıcı ----------------

  var oynatici = null;     // YT.Player
  var oynaticiHazir = false;
  var apiIstendi = false;
  var yedekMod = false;    // IFrame API yüklenemezse düz iframe
  var bekleyen = null;     // oynatıcı hazır olmadan seçilen video
  var aktif = null;        // şu an yüklü video
  var sonKayit = 0;
  var SAYFA_BASLIGI = document.title;
  var kuyruk = [];         // videonun açıldığı listenin kopyası (sıradaki için)
  var siradakiZamani = null;

  // YouTube betiği yalnızca ilk video seçildiğinde yüklenir.
  function oynaticiyiKur() {
    if (apiIstendi) return;
    apiIstendi = true;
    if (window.YT && window.YT.Player) return oynaticiOlustur();
    window.onYouTubeIframeAPIReady = oynaticiOlustur;
    var s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    s.async = true;
    s.onerror = yedegeGec;
    document.head.appendChild(s);
    setTimeout(function () { if (!oynaticiHazir) yedegeGec(); }, 10000);
  }

  function oynaticiOlustur() {
    if (yedekMod) return;
    oynatici = new YT.Player('oynatici', {
      host: 'https://www.youtube-nocookie.com',
      playerVars: { playsinline: 1, rel: 0, modestbranding: 1 },
      events: {
        onReady: function () {
          oynaticiHazir = true;
          if (bekleyen) { var b = bekleyen; bekleyen = null; yukle(b.video, b.baslangic, b.oynat); }
        },
        onStateChange: function (e) {
          if (e.data === YT.PlayerState.PAUSED || e.data === YT.PlayerState.ENDED) ilerlemeKaydet(true);
          if (e.data === YT.PlayerState.ENDED) videoBitti();
          if (e.data === YT.PlayerState.PLAYING) siradakiIptal();
        },
        onError: function (e) {
          var mesaj = { 2: 'Geçersiz video', 5: 'Video oynatılamadı', 100: 'Video bulunamadı ya da kaldırılmış', 101: 'Bu video başka sitelerde oynatılamıyor', 150: 'Bu video başka sitelerde oynatılamıyor' }[e.data];
          if (aktif && (e.data === 101 || e.data === 150)) {
            var id = aktif.id;
            bildir(mesaj, 'YouTube’da aç', function () { window.open('https://www.youtube.com/watch?v=' + id, '_blank', 'noopener'); });
          } else bildir(mesaj || 'Video oynatılamadı');
        },
      },
    });
  }

  // IFrame API'ye ulaşılamazsa video yine de düz iframe ile oynar (ilerleme izlenemez).
  function yedegeGec() {
    if (oynaticiHazir || yedekMod) return;
    yedekMod = true;
    if (bekleyen) { var b = bekleyen; bekleyen = null; yukle(b.video, b.baslangic, b.oynat); }
  }

  function yukle(video, baslangic, oynat) {
    if (yedekMod) {
      var kap = $('video-kutusu');
      var iframe = kap.querySelector('iframe') || document.createElement('iframe');
      iframe.title = video.baslik || 'YouTube video';
      iframe.allow = 'accelerometer; autoplay; encrypted-media; picture-in-picture; fullscreen';
      iframe.allowFullscreen = true;
      iframe.src = 'https://www.youtube-nocookie.com/embed/' + video.id + '?playsinline=1&rel=0' +
        (baslangic ? '&start=' + Math.floor(baslangic) : '') + (oynat ? '&autoplay=1' : '');
      if (!iframe.parentNode) { kap.textContent = ''; kap.appendChild(iframe); }
      return;
    }
    if (!oynaticiHazir) { bekleyen = { video: video, baslangic: baslangic, oynat: oynat }; return; }
    var parametre = { videoId: video.id, startSeconds: baslangic || 0 };
    if (oynat) oynatici.loadVideoById(parametre);
    else oynatici.cueVideoById(parametre);
  }

  function oynayanIsaretle() {
    document.querySelectorAll('.result.playing').forEach(function (r) { r.classList.remove('playing'); });
    if (!aktif) return;
    document.querySelectorAll('.result[data-id="' + aktif.id + '"]').forEach(function (r) { r.classList.add('playing'); });
  }

  function siradakiVideo() {
    if (!aktif) return null;
    var i = -1;
    for (var j = 0; j < kuyruk.length; j++) if (kuyruk[j].id === aktif.id) { i = j; break; }
    return i >= 0 && i + 1 < kuyruk.length ? kuyruk[i + 1] : null;
  }

  function siradakiCiz() {
    var v = siradakiVideo();
    var d = $('siradaki');
    d.hidden = !v;
    if (!v) return;
    $('siradaki-baslik').textContent = v.baslik;
    $('siradaki-resim').src = kucukResimAdresi(v);
    d.title = 'Sıradaki: ' + v.baslik;
  }

  function siradakineGec(oynat) {
    var v = siradakiVideo();
    siradakiIptal();
    if (v) videoAc(v, { kullanici: true, liste: kuyruk, oynat: oynat !== false });
    else bildir('Listede sıradaki video yok');
  }

  function siradakiIptal() {
    if (siradakiZamani) { clearTimeout(siradakiZamani); siradakiZamani = null; bildirimiKapat(); }
  }

  function videoBitti() {
    var v = siradakiVideo();
    if (!v || !ayarlar.siradakiniOynat) return;
    siradakiIptal();
    bildir('Sıradaki: ' + v.baslik, 'İptal', function () { siradakiIptal(); }, 5200);
    siradakiZamani = setTimeout(function () {
      siradakiZamani = null;
      siradakineGec(true);
    }, 5000);
  }

  $('siradaki').addEventListener('click', function () { siradakineGec(true); });

  function simdiOynuyorCiz() {
    if (!aktif) return;
    $('simdi-baslik').textContent = aktif.baslik;
    $('simdi-kanal').textContent = aktif.kanal || '';
    $('youtubeda-ac').href = 'https://www.youtube.com/watch?v=' + aktif.id;
    document.title = aktif.baslik + ' · ' + SAYFA_BASLIGI;
    siradakiCiz();
    kayitIsaretleriniGuncelle();
  }

  // kullanici: kullanıcı seçti (geçmişe eklenir) · liste: sıradaki için kaynak liste
  // oynat: otomatik oynat ayarından bağımsız olarak başlat
  function videoAc(video, secenek) {
    secenek = secenek || {};
    siradakiIptal();
    ilerlemeKaydet(true); // önceki videonun yerini kaydet
    if (secenek.liste) kuyruk = secenek.liste.map(videoOzeti);
    else if (!kuyruk.some(function (k) { return k.id === video.id; })) kuyruk = [];
    var kayit = gecmisHaritasi[video.id];
    var baslangic = 0;
    if (devamGoster() && kayit && kayit.konum > 5 && kayit.ilerleme < 95) baslangic = kayit.konum;
    var oncedenAcikMi = !!aktif;
    aktif = {
      id: video.id,
      baslik: video.baslik,
      kanal: video.kanal || (kayit && kayit.kanal) || '',
      sure: video.sure || (kayit && kayit.sure) || '',
      kucukResim: video.kucukResim || (kayit && kayit.kucukResim) || '',
      canli: !!video.canli,
    };
    oynaticiyiKur();
    var oynat = !!secenek.oynat || (secenek.kullanici && !!(ayarlar && ayarlar.otomatikOynat));
    yukle(aktif, baslangic, oynat);
    simdiOynuyorCiz();
    if (gecerliSayfa !== 'home') git(anaSayfaAdresi());
    anaGorunumuGuncelle();
    // Oynatıcı ekranda değilse göster (mobilde sonuçların altında kalmasın).
    var kutu = $('video-kutusu');
    var r = kutu.getBoundingClientRect();
    if (!oncedenAcikMi || r.bottom < 0 || r.top > window.innerHeight) {
      kutu.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
    if (baslangic && !secenek.oynat) bildir(sureMetni(baslangic) + ' noktasından devam ediliyor', 'Baştan başlat', function () {
      if (oynaticiHazir) oynatici.seekTo(0, true);
      else if (aktif) yukle(aktif, 0, true);
    });
    if (secenek.kullanici) gecmiseEkle(aktif);
  }

  function oynaticiyiKapat() {
    siradakiIptal();
    ilerlemeKaydet(true);
    if (oynaticiHazir && oynatici.stopVideo) oynatici.stopVideo();
    if (yedekMod) { var f = $('video-kutusu').querySelector('iframe'); if (f) f.src = 'about:blank'; }
    bekleyen = null;
    aktif = null;
    document.title = SAYFA_BASLIGI;
    anaGorunumuGuncelle();
  }

  $('oynaticiyi-kapat').addEventListener('click', oynaticiyiKapat);
  $('simdi-kaydet').addEventListener('click', function () { if (aktif) kaydetDegistir(aktif); });

  // Telefonlarda sistemin paylaş menüsü, diğerlerinde panoya kopyalama.
  var paylasilabilir = !!navigator.share && matchMedia('(hover: none)').matches;
  if (paylasilabilir) {
    var pd = $('linki-kopyala');
    pd.title = 'Paylaş';
    pd.setAttribute('aria-label', 'Paylaş');
    pd.textContent = '';
    pd.appendChild(ikon('paylas'));
  }
  $('linki-kopyala').addEventListener('click', function () {
    if (!aktif) return;
    var link = 'https://youtu.be/' + aktif.id;
    if (paylasilabilir) {
      navigator.share({ title: aktif.baslik, url: link }).catch(function () {});
      return;
    }
    var bitti = function () { bildir('Link kopyalandı'); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(link).then(bitti, function () { eskiKopyala(link) ? bitti() : bildir(link); });
    } else if (eskiKopyala(link)) bitti();
    else bildir(link);
  });
  function eskiKopyala(metin) {
    var t = el('textarea');
    t.value = metin;
    t.style.position = 'fixed';
    t.style.opacity = '0';
    document.body.appendChild(t);
    t.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    t.remove();
    return ok;
  }

  function gecmiseEkle(video) {
    if (ayarlar && !ayarlar.gecmisiKaydet) return;
    var liste = gecmisiOku();
    var eski = null;
    liste = liste.filter(function (k) {
      if (k.id === video.id) { eski = k; return false; }
      return true;
    });
    liste.unshift({
      id: video.id,
      baslik: video.baslik,
      kanal: video.kanal || (eski && eski.kanal) || '',
      sure: video.sure || (eski && eski.sure) || '',
      kucukResim: video.kucukResim || (eski && eski.kucukResim) || '',
      ilerleme: eski ? eski.ilerleme || 0 : 0,
      konum: eski ? eski.konum || 0 : 0,
      izlenme: new Date().toISOString(),
    });
    gecmisiYaz(liste);
    gecmisiYukle();
  }

  // İzleme ilerlemesini yerel geçmişe yazar (oynarken en sık 5 sn'de bir).
  function ilerlemeKaydet(hemen) {
    if (!aktif || !oynaticiHazir || !oynatici.getDuration) return;
    if (ayarlar && !ayarlar.gecmisiKaydet) return;
    if (!gecmisHaritasi[aktif.id]) return;
    var toplam = oynatici.getDuration();
    var konum = oynatici.getCurrentTime();
    if (!toplam || !(konum >= 0)) return;
    if (oynatici.getVideoData && oynatici.getVideoData().video_id !== aktif.id) return;
    var simdi = Date.now();
    if (!hemen && simdi - sonKayit < 5000) return;
    sonKayit = simdi;
    var ilerleme = Math.min(100, Math.round((konum / toplam) * 100));
    if (oynatici.getPlayerState() === YT.PlayerState.ENDED) ilerleme = 100;
    konum = Math.floor(konum);

    // Başka sekmede değişmiş olabilir: güncel listeyi okuyup yalnızca bu kaydı güncelle.
    var liste = gecmisiOku();
    var kayit = liste.filter(function (k) { return k.id === aktif.id; })[0];
    if (!kayit) return;
    var sureYok = !kayit.sure && !aktif.canli;
    if (kayit.ilerleme === ilerleme && kayit.konum === konum && !sureYok) return;
    kayit.ilerleme = ilerleme;
    kayit.konum = konum;
    if (sureYok) kayit.sure = sureMetni(toplam);
    gecmisiYaz(liste);
    gecmisiYukle();
  }

  setInterval(function () {
    if (oynaticiHazir && oynatici.getPlayerState && oynatici.getPlayerState() === YT.PlayerState.PLAYING) ilerlemeKaydet(false);
  }, 2000);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') ilerlemeKaydet(true);
  });
  window.addEventListener('pagehide', function () { ilerlemeKaydet(true); });

  // Başka bir sekmede veriler değişirse bu sekmeyi de güncelle.
  window.addEventListener('storage', function (e) {
    if (e.key === ANAHTAR_GECMIS) gecmisiYukle();
    if (e.key === ANAHTAR_AYARLAR) {
      ayarlar = ayarlariOku();
      ayarlariFormaYaz();
      gecmisiYukle();
    }
    if (e.key === ANAHTAR_ARAMALAR && !arama.q) sonuclariCiz();
    if (e.key === ANAHTAR_KAYDEDILENLER) kaydedilenleriYukle();
  });

  // ---------------- Klavye kısayolları ----------------
  // /  arama kutusuna git · K  oynat/duraklat · J / L  10 sn geri/ileri · N  sıradaki · Esc  öneri listesini kapat

  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey || yaziyorMu(e.target)) return;
    if (e.key === '/') {
      e.preventDefault();
      if (gecerliSayfa !== 'home') git(anaSayfaAdresi());
      aramaKutusu.focus();
      aramaKutusu.select();
    } else if ((e.key === 'k' || e.key === 'K') && aktif && oynaticiHazir) {
      if (oynatici.getPlayerState() === YT.PlayerState.PLAYING) oynatici.pauseVideo();
      else oynatici.playVideo();
    } else if ((e.key === 'j' || e.key === 'J' || e.key === 'l' || e.key === 'L') && aktif && oynaticiHazir) {
      var fark = e.key.toLowerCase() === 'j' ? -10 : 10;
      var hedef = Math.max(0, oynatici.getCurrentTime() + fark);
      var toplam = oynatici.getDuration();
      if (toplam) hedef = Math.min(hedef, toplam - 1);
      oynatici.seekTo(hedef, true);
      bildir((fark < 0 ? '« 10 sn geri · ' : '10 sn ileri » · ') + sureMetni(hedef), null, null, 1200);
    } else if ((e.key === 'n' || e.key === 'N') && aktif) {
      siradakineGec(true);
    }
  });

  // ---------------- Tıklamalar ----------------

  function tiklaVeKlavye(kap, secici, isle) {
    kap.addEventListener('click', function (e) {
      var s = e.target.closest(secici);
      if (s) isle(s, e);
    });
    kap.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      var s = e.target.closest(secici);
      if (s && e.target === s) { e.preventDefault(); isle(s, e); }
    });
  }

  // Bir listedeki satır tıklamaları: kaydet düğmesi, kaldır düğmesi ya da videoyu aç.
  function listeBagla(kap, bul, liste, kaldir) {
    tiklaVeKlavye(kap, '.result[data-id]', function (satir, e) {
      var video = bul(satir.dataset.id);
      if (!video) return;
      if (e.target.closest('.save-btn')) return kaydetDegistir(video);
      if (e.target.closest('.remove-btn')) return kaldir && kaldir(video.id);
      videoAc(video, { kullanici: true, liste: liste() });
    });
    if (kaldir) {
      // Seçili satırda Delete tuşu kaydı kaldırır.
      kap.addEventListener('keydown', function (e) {
        if ((e.key === 'Delete' || e.key === 'Backspace') && e.target.matches('.result[data-id]')) {
          e.preventDefault();
          kaldir(e.target.dataset.id);
        }
      });
    }
  }

  listeBagla($('sonuclar'),
    function (id) { return sonucHaritasi[id]; },
    function () { return arama.sonuclar; });
  listeBagla($('gecmis-listesi'),
    function (id) { return gecmisHaritasi[id]; },
    function () { return gecmis; },
    gecmistenKaldir);
  listeBagla($('kaydedilen-listesi'),
    function (id) { return kaydedilenHaritasi[id]; },
    function () { return kaydedilenler; },
    function (id) { kaydetDegistir(kaydedilenHaritasi[id]); });

  tiklaVeKlavye($('son-izlenenler'), '.card[data-id]', function (s) {
    var k = gecmisHaritasi[s.dataset.id];
    if (k) videoAc(k, { kullanici: true, liste: gecmis });
  });

  $('hepsini-oynat').addEventListener('click', function () {
    if (kaydedilenler.length) videoAc(kaydedilenler[0], { kullanici: true, liste: kaydedilenler, oynat: true });
  });
  $('kaydedilenleri-temizle').addEventListener('click', kaydedilenleriTemizle);

  $('gecmisi-temizle').addEventListener('click', gecmisiTemizle);
  $('ayarlardan-gecmisi-temizle').addEventListener('click', gecmisiTemizle);
  $('aramalari-temizle').addEventListener('click', function () {
    var eski = aramalariOku();
    if (!eski.length) return bildir('Son arama yok');
    yerelYaz(ANAHTAR_ARAMALAR, []);
    if (!arama.q) sonuclariCiz();
    bildir('Son aramalar temizlendi', 'Geri al', function () {
      yerelYaz(ANAHTAR_ARAMALAR, eski);
      if (!arama.q) sonuclariCiz();
    });
  });

  // ---------------- Dışa / içe aktarma ----------------
  // Veriler yalnızca bu tarayıcıda olduğundan başka cihaza taşımak için dosya kullanılır.

  $('disa-aktar').addEventListener('click', function () {
    var veri = {
      uygulama: 'arama',
      surum: 1,
      tarih: new Date().toISOString(),
      ayarlar: ayarlar,
      gecmis: gecmisiOku(),
      kaydedilenler: kaydedilenleriOku(),
      sonAramalar: aramalariOku(),
    };
    var blob = new Blob([JSON.stringify(veri, null, 2)], { type: 'application/json' });
    var a = el('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'arama-yedek-' + new Date().toISOString().slice(0, 10) + '.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
    bildir('Yedek dosyası indirildi');
  });

  var dosyaGirdisi = $('ice-aktar-dosya');
  $('ice-aktar').addEventListener('click', function () { dosyaGirdisi.click(); });
  dosyaGirdisi.addEventListener('change', function () {
    var dosya = dosyaGirdisi.files && dosyaGirdisi.files[0];
    dosyaGirdisi.value = '';
    if (!dosya) return;
    if (dosya.size > 5 * 1024 * 1024) return bildir('Dosya çok büyük');
    var okuyucu = new FileReader();
    okuyucu.onload = function () {
      var veri;
      try { veri = JSON.parse(okuyucu.result); } catch (e) { return bildir('Dosya okunamadı: geçerli bir yedek değil'); }
      if (!veri || veri.uygulama !== 'arama') return bildir('Bu dosya bir Arama yedeği değil');
      var gelenGecmis = (Array.isArray(veri.gecmis) ? veri.gecmis : []).filter(function (k) { return videoGecerliMi(k) && typeof k.izlenme === 'string'; });
      var gelenKayit = (Array.isArray(veri.kaydedilenler) ? veri.kaydedilenler : []).filter(videoGecerliMi);
      var gelenArama = (Array.isArray(veri.sonAramalar) ? veri.sonAramalar : []).filter(function (q) { return typeof q === 'string'; });
      if (!confirm('Yedek yüklensin mi?\n\n' + gelenGecmis.length + ' geçmiş kaydı, ' + gelenKayit.length +
        ' kaydedilen video ve ayarlar bu tarayıcıdakilerin yerine geçecek.')) return;
      var onceki = { gecmis: gecmisiOku(), kayit: kaydedilenleriOku(), arama: aramalariOku(), ayarlar: JSON.parse(JSON.stringify(ayarlar)) };
      gecmisiYaz(gelenGecmis);
      kaydedilenleriYaz(gelenKayit);
      yerelYaz(ANAHTAR_ARAMALAR, gelenArama.slice(0, ARAMA_SINIRI));
      if (veri.ayarlar && typeof veri.ayarlar === 'object') yerelYaz(ANAHTAR_AYARLAR, veri.ayarlar);
      tumunuYenidenYukle();
      bildir('Yedek yüklendi', 'Geri al', function () {
        gecmisiYaz(onceki.gecmis);
        kaydedilenleriYaz(onceki.kayit);
        yerelYaz(ANAHTAR_ARAMALAR, onceki.arama);
        yerelYaz(ANAHTAR_AYARLAR, onceki.ayarlar);
        tumunuYenidenYukle();
      });
    };
    okuyucu.onerror = function () { bildir('Dosya okunamadı'); };
    okuyucu.readAsText(dosya);
  });

  function tumunuYenidenYukle() {
    ayarlar = ayarlariOku(); // dosyadan gelen ayarlar da burada doğrulanır
    ayarlariFormaYaz();
    kaydedilenleriYukle();
    gecmisiYukle();
  }

  // ---------------- Ayarlar ----------------

  function temayiUygula(tema) {
    var kok = document.documentElement;
    if (tema === 'acik') kok.dataset.theme = 'light';
    else if (tema === 'koyu') kok.dataset.theme = 'dark';
    else delete kok.dataset.theme;
  }

  function ayarlariFormaYaz() {
    document.querySelectorAll('input[name="tema"]').forEach(function (r) {
      r.checked = r.value === ayarlar.tema;
    });
    document.querySelectorAll('[data-ayar]').forEach(function (girdi) {
      var deger = ayarlar[girdi.dataset.ayar];
      if (girdi.type === 'checkbox') girdi.checked = !!deger;
      else girdi.value = deger;
    });
    temayiUygula(ayarlar.tema);
  }

  function ayarKaydet(degisiklik) {
    Object.keys(degisiklik).forEach(function (k) { ayarlar[k] = degisiklik[k]; });
    yerelYaz(ANAHTAR_AYARLAR, ayarlar);
    ayarlariFormaYaz();
    gecmisiYukle();
  }

  document.querySelectorAll('input[name="tema"]').forEach(function (r) {
    r.addEventListener('change', function () { ayarKaydet({ tema: r.value }); });
  });

  document.querySelectorAll('input[type="checkbox"][data-ayar]').forEach(function (kutu) {
    kutu.addEventListener('change', function () {
      var d = {};
      d[kutu.dataset.ayar] = kutu.checked;
      ayarKaydet(d);
    });
  });

  var adGirdisi = document.querySelector('[data-ayar="kullaniciAdi"]');
  adGirdisi.addEventListener('change', function () {
    var ad = adGirdisi.value.trim();
    if (!ad) {
      adGirdisi.value = ayarlar ? ayarlar.kullaniciAdi : 'Misafir';
      return;
    }
    ayarKaydet({ kullaniciAdi: ad.slice(0, 32) });
    bildir('Kullanıcı adı kaydedildi');
  });
  adGirdisi.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') adGirdisi.blur();
  });

  // ---------------- Başlangıç ----------------

  ayarlar = ayarlariOku();
  ayarlariFormaYaz();
  kaydedilenleriYukle();
  gecmisiYukle();
  menuAdresleriniGuncelle();
  // Sayfa açılışındaki aramada link videoyu kendiliğinden açmaz, listede gösterir.
  arama.q = null; // ilk adresi her durumda uygula
  adresiUygula(false);
})();
