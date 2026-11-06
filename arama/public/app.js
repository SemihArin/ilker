(function () {
  'use strict';

  var ayarlar = null;
  var gecmis = [];
  var gecmisHaritasi = {};

  // ---------------- Yardımcılar ----------------

  function api(yol, secenek) {
    secenek = secenek || {};
    var basliklar = {};
    if (secenek.govde !== undefined) basliklar['Content-Type'] = 'application/json';
    return fetch('/api' + yol, {
      method: secenek.yontem || 'GET',
      headers: basliklar,
      body: secenek.govde !== undefined ? JSON.stringify(secenek.govde) : undefined,
      keepalive: !!secenek.keepalive,
    }).then(function (yanit) {
      if (yanit.status === 204) return null;
      return yanit.json().catch(function () { return {}; }).then(function (veri) {
        if (!yanit.ok) {
          var hata = new Error(veri.hata || 'İstek başarısız (' + yanit.status + ')');
          hata.kod = veri.kod;
          hata.durum = yanit.status;
          throw hata;
        }
        return veri;
      });
    });
  }

  function el(etiket, sinif, metin) {
    var e = document.createElement(etiket);
    if (sinif) e.className = sinif;
    if (metin !== undefined) e.textContent = metin;
    return e;
  }

  function $(id) { return document.getElementById(id); }

  var toastZamani;
  function bildir(mesaj) {
    var t = $('toast');
    t.textContent = mesaj;
    t.classList.add('show');
    clearTimeout(toastZamani);
    toastZamani = setTimeout(function () { t.classList.remove('show'); }, 2600);
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

  // ---------------- Sayfa geçişleri ----------------

  var linkler = document.querySelectorAll('.menu a[data-page]');
  var sayfalar = document.querySelectorAll('.page');

  function goster(ad) {
    sayfalar.forEach(function (p) { p.hidden = p.id !== 'p-' + ad; });
    linkler.forEach(function (a) {
      var aktif = a.dataset.page === ad;
      a.classList.toggle('active', aktif);
      if (aktif) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
    window.scrollTo(0, 0);
  }

  linkler.forEach(function (a) {
    a.addEventListener('click', function (e) {
      e.preventDefault();
      goster(a.dataset.page);
    });
  });
  document.querySelector('.see-all-right').addEventListener('click', function () { goster('gecmis'); });

  // ---------------- Video satırı ----------------

  function videoSatiri(video, secenek) {
    secenek = secenek || {};
    var kayit = gecmisHaritasi[video.id];
    var satir = el('div', 'result');
    satir.dataset.id = video.id;
    satir.tabIndex = 0;
    satir.setAttribute('role', 'button');
    if (aktif && aktif.id === video.id) satir.classList.add('playing');

    var thumb = el('div', 'thumb');
    thumb.appendChild(resim(kucukResimAdresi(video)));
    if (video.canli) thumb.appendChild(el('span', 'duration live', 'CANLI'));
    else if (video.sure || (kayit && kayit.sure)) thumb.appendChild(el('span', 'duration', video.sure || kayit.sure));
    if (devamGoster() && kayit && kayit.ilerleme > 0) thumb.appendChild(ilerlemeCubugu('thumb-bar', kayit.ilerleme));
    satir.appendChild(thumb);

    var bilgi = el('div');
    bilgi.appendChild(el('h3', 'result-title', video.baslik));
    bilgi.appendChild(el('div', 'result-channel', video.kanal || ''));
    var meta = [];
    if (secenek.gecmis) meta.push(goreliZaman(video.izlenme));
    else {
      if (typeof video.izlenmeSayisi === 'number') meta.push(sayiBicimi.format(video.izlenmeSayisi) + ' görüntüleme');
      if (video.yayinTarihi) meta.push(goreliZaman(video.yayinTarihi));
    }
    if (meta.length) bilgi.appendChild(el('div', 'result-meta', meta.join(' · ')));
    satir.appendChild(bilgi);

    if (secenek.gecmis) {
      var sil = el('button', 'remove', '×');
      sil.type = 'button';
      sil.title = 'Geçmişten kaldır';
      sil.setAttribute('aria-label', 'Geçmişten kaldır: ' + video.baslik);
      satir.appendChild(sil);
    }
    return satir;
  }

  // ---------------- Geçmiş ----------------

  function gecmisSayfasiniCiz() {
    var kap = $('gecmis-listesi');
    kap.textContent = '';
    $('gecmisi-temizle').hidden = gecmis.length === 0;
    if (!gecmis.length) {
      kap.appendChild(el('div', 'empty', ayarlar && !ayarlar.gecmisiKaydet
        ? 'Geçmiş kaydı kapalı. Ayarlar’dan açabilirsin.'
        : 'Henüz izlenen video yok.'));
      return;
    }
    var sonGrup = null;
    var liste;
    gecmis.forEach(function (k) {
      var grup = grupAdi(k.izlenme);
      if (grup !== sonGrup) {
        kap.appendChild(el('h2', 'section-title', grup));
        liste = el('div', 'list');
        kap.appendChild(liste);
        sonGrup = grup;
      }
      liste.appendChild(videoSatiri(k, { gecmis: true }));
    });
  }

  function kartlariCiz() {
    var kap = $('son-izlenenler');
    kap.textContent = '';
    if (!gecmis.length) {
      kap.appendChild(el('div', 'empty', 'İzlediğin videolar burada görünecek.'));
      return;
    }
    gecmis.slice(0, 10).forEach(function (k) {
      var kart = el('div', 'card');
      kart.dataset.id = k.id;
      kart.tabIndex = 0;
      kart.setAttribute('role', 'button');
      var img = el('div', 'card-img');
      img.appendChild(resim(kucukResimAdresi(k)));
      kart.appendChild(img);
      var govde = el('div', 'card-body');
      govde.appendChild(el('h3', 'card-title', k.baslik));
      if (devamGoster()) govde.appendChild(ilerlemeCubugu('bar', k.ilerleme || 0));
      kart.appendChild(govde);
      kap.appendChild(kart);
    });
  }

  function gecmisiCiz() {
    gecmisSayfasiniCiz();
    kartlariCiz();
    sonuclariCiz();
  }

  function gecmisiAyarla(liste) {
    gecmis = liste;
    gecmisHaritasi = {};
    gecmis.forEach(function (k) { gecmisHaritasi[k.id] = k; });
  }

  function gecmisiYukle() {
    return api('/gecmis').then(function (liste) {
      gecmisiAyarla(liste);
      gecmisiCiz();
    });
  }

  // ---------------- Arama ----------------

  var arama = { q: '', sonuclar: [], sonrakiSayfa: null, yukleniyor: false, istek: 0 };
  var sonucHaritasi = {};

  function iskeletCiz(adet) {
    var kap = $('sonuclar');
    kap.textContent = '';
    for (var i = 0; i < adet; i++) {
      var s = el('div', 'result skeleton');
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
    if (arama.hata) {
      kap.appendChild(el('div', 'empty hata', arama.hata));
    } else if (!arama.q) {
      kap.appendChild(el('div', 'empty', 'İzlemek istediğin videoyu ara ya da bir YouTube linki yapıştır.'));
    } else if (!arama.sonuclar.length) {
      kap.appendChild(el('div', 'empty', '“' + arama.q + '” için sonuç bulunamadı.'));
    }
    arama.sonuclar.forEach(function (v) { kap.appendChild(videoSatiri(v)); });

    var baslik = $('sonuc-basligi');
    baslik.hidden = !arama.q || !!arama.hata;
    baslik.textContent = /^(https?:\/\/|www\.|youtu)/i.test(arama.q) ? 'Bağlantıdaki video' : '“' + arama.q + '” için sonuçlar';

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
      sonucHaritasi = {};
      try { if (q) localStorage.setItem('sonArama', q); else localStorage.removeItem('sonArama'); } catch (e) {}
      var url = new URL(location.href);
      if (q) url.searchParams.set('q', q); else url.searchParams.delete('q');
      history.replaceState(null, '', url);
      if (!q) { sonuclariCiz(); return Promise.resolve(); }
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
        // Link yapıştırıldıysa videoyu doğrudan aç.
        if (linkiAc && !devam && yanit.sonuclar.length === 1 && !yanit.sonrakiSayfa && /youtu|^[\w-]{11}$/.test(q)) {
          videoAc(yanit.sonuclar[0], { kullanici: true });
        }
      })
      .catch(function (err) {
        if (no !== arama.istek) return;
        if (devam) bildir(err.message);
        else arama.hata = err.message;
      })
      .then(function () {
        if (no !== arama.istek) return;
        arama.yukleniyor = false;
        sonuclariCiz();
      });
  }

  var aramaKutusu = $('arama');
  $('arama-formu').addEventListener('submit', function (e) {
    e.preventDefault();
    aramaKutusu.blur(); // mobilde klavyeyi kapat
    aramaYap(aramaKutusu.value, false, true);
  });
  // Arama kutusundaki × ile temizlenince sonuçları da temizle.
  aramaKutusu.addEventListener('search', function () {
    if (!aramaKutusu.value) aramaYap('');
  });
  $('daha-fazla').addEventListener('click', function () {
    if (!arama.yukleniyor && arama.sonrakiSayfa) aramaYap(arama.q, true);
  });

  // ---------------- Oynatıcı ----------------

  var oynatici = null;     // YT.Player
  var oynaticiHazir = false;
  var yedekMod = false;    // IFrame API yüklenemezse düz iframe
  var bekleyen = null;     // oynatıcı hazır olmadan seçilen video
  var aktif = null;        // şu an yüklü video
  var sonKayit = 0;

  function oynaticiyiKur() {
    if (window.YT && window.YT.Player) return oynaticiOlustur();
    window.onYouTubeIframeAPIReady = oynaticiOlustur;
    var s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    s.async = true;
    s.onerror = function () { yedegeGec(); };
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
        },
        onError: function (e) {
          var mesaj = { 2: 'Geçersiz video', 5: 'Video oynatılamadı', 100: 'Video bulunamadı ya da kaldırılmış', 101: 'Bu video gömülü oynatmaya kapalı', 150: 'Bu video gömülü oynatmaya kapalı' }[e.data];
          bildir(mesaj || 'Video oynatılamadı');
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
    $('video-bos').hidden = true;
    if (yedekMod) {
      var kap = $('oynatici');
      var iframe = kap.querySelector('iframe') || document.createElement('iframe');
      iframe.title = video.baslik || 'YouTube video';
      iframe.allow = 'accelerometer; autoplay; encrypted-media; picture-in-picture; fullscreen';
      iframe.allowFullscreen = true;
      iframe.src = 'https://www.youtube-nocookie.com/embed/' + video.id + '?playsinline=1&rel=0' +
        (baslangic ? '&start=' + Math.floor(baslangic) : '') + (oynat ? '&autoplay=1' : '');
      if (!iframe.parentNode) kap.appendChild(iframe);
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

  // kullanici: kullanıcı seçti (geçmişe eklenir); değilse sadece hazırlanır.
  function videoAc(video, secenek) {
    secenek = secenek || {};
    ilerlemeKaydet(true); // önceki videonun yerini kaydet
    var kayit = gecmisHaritasi[video.id];
    var baslangic = 0;
    if (devamGoster() && kayit && kayit.konum > 5 && kayit.ilerleme < 95) baslangic = kayit.konum;
    aktif = {
      id: video.id,
      baslik: video.baslik,
      kanal: video.kanal || (kayit && kayit.kanal) || '',
      sure: video.sure || (kayit && kayit.sure) || '',
      kucukResim: video.kucukResim || (kayit && kayit.kucukResim) || '',
      canli: !!video.canli,
    };
    var oynat = secenek.kullanici && !!(ayarlar && ayarlar.otomatikOynat);
    yukle(aktif, baslangic, oynat);
    oynayanIsaretle();
    if (secenek.kullanici) {
      goster('home');
      if (baslangic) bildir(sureMetni(baslangic) + ' noktasından devam ediliyor');
      gecmiseEkle(aktif);
    }
  }

  function gecmiseEkle(video) {
    if (ayarlar && !ayarlar.gecmisiKaydet) return;
    var govde = { id: video.id, baslik: video.baslik };
    if (video.kanal) govde.kanal = video.kanal;
    if (video.sure) govde.sure = video.sure;
    if (video.kucukResim) govde.kucukResim = video.kucukResim;
    api('/gecmis', { yontem: 'POST', govde: govde })
      .then(gecmisiYukle)
      .catch(function (err) { bildir(err.message); });
  }

  // İzleme ilerlemesini sunucuya yazar (oynarken en sık 10 sn'de bir).
  function ilerlemeKaydet(hemen, cikis) {
    if (!aktif || !oynaticiHazir || !oynatici.getDuration) return;
    if (ayarlar && !ayarlar.gecmisiKaydet) return;
    if (!gecmisHaritasi[aktif.id]) return;
    var toplam = oynatici.getDuration();
    var konum = oynatici.getCurrentTime();
    if (!toplam || !(konum >= 0)) return;
    if (oynatici.getVideoData && oynatici.getVideoData().video_id !== aktif.id) return;
    var simdi = Date.now();
    if (!hemen && simdi - sonKayit < 10000) return;
    sonKayit = simdi;
    var ilerleme = Math.min(100, Math.round((konum / toplam) * 100));
    if (oynatici.getPlayerState() === YT.PlayerState.ENDED) ilerleme = 100;
    var govde = { ilerleme: ilerleme, konum: Math.floor(konum) };
    var kayit = gecmisHaritasi[aktif.id];
    if (!kayit.sure && toplam > 0 && !aktif.canli) govde.sure = sureMetni(toplam);
    if (kayit.ilerleme === govde.ilerleme && kayit.konum === govde.konum && !govde.sure) return;
    // Yerel kopyayı hemen güncelle ki çubuklar anında değişsin.
    kayit.ilerleme = govde.ilerleme;
    kayit.konum = govde.konum;
    if (govde.sure) kayit.sure = govde.sure;
    var istek = api('/gecmis/' + encodeURIComponent(aktif.id), { yontem: 'PATCH', govde: govde, keepalive: !!cikis });
    if (!cikis) istek.then(function () { gecmisiCiz(); oynayanIsaretle(); }).catch(function () {});
  }

  setInterval(function () {
    if (oynaticiHazir && oynatici.getPlayerState && oynatici.getPlayerState() === YT.PlayerState.PLAYING) ilerlemeKaydet(false);
  }, 2000);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') ilerlemeKaydet(true, true);
  });
  window.addEventListener('pagehide', function () { ilerlemeKaydet(true, true); });

  // ---------------- Tıklamalar ----------------

  function satirBul(e, secici) {
    return e.target.closest(secici);
  }

  function tiklaVeKlavye(kap, secici, isle) {
    kap.addEventListener('click', function (e) {
      var s = satirBul(e, secici);
      if (s) isle(s, e);
    });
    kap.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      var s = satirBul(e, secici);
      if (s && e.target === s) { e.preventDefault(); isle(s, e); }
    });
  }

  tiklaVeKlavye($('sonuclar'), '.result[data-id]', function (s) {
    var v = sonucHaritasi[s.dataset.id];
    if (v) videoAc(v, { kullanici: true });
  });

  tiklaVeKlavye($('son-izlenenler'), '.card[data-id]', function (s) {
    var k = gecmisHaritasi[s.dataset.id];
    if (k) videoAc(k, { kullanici: true });
  });

  tiklaVeKlavye($('gecmis-listesi'), '.result[data-id]', function (s, e) {
    var id = s.dataset.id;
    if (e.target.closest('.remove')) {
      api('/gecmis/' + encodeURIComponent(id), { yontem: 'DELETE' })
        .then(function () {
          bildir('Geçmişten kaldırıldı');
          return gecmisiYukle();
        })
        .catch(function (err) { bildir(err.message); });
      return;
    }
    var k = gecmisHaritasi[id];
    if (k) videoAc(k, { kullanici: true });
  });

  $('gecmisi-temizle').addEventListener('click', function () {
    if (!confirm('Tüm izleme geçmişi silinsin mi?')) return;
    api('/gecmis', { yontem: 'DELETE' })
      .then(function () {
        bildir('Geçmiş temizlendi');
        return gecmisiYukle();
      })
      .catch(function (err) { bildir(err.message); });
  });

  // ---------------- Ayarlar ----------------

  function temayiUygula(tema) {
    var kok = document.documentElement;
    if (tema === 'acik') kok.dataset.theme = 'light';
    else if (tema === 'koyu') kok.dataset.theme = 'dark';
    else delete kok.dataset.theme;
    try { localStorage.setItem('tema', tema); } catch (e) {}
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
    return api('/ayarlar', { yontem: 'PATCH', govde: degisiklik })
      .then(function (yeni) {
        ayarlar = yeni;
        ayarlariFormaYaz();
        gecmisiCiz();
        oynayanIsaretle();
      })
      .catch(function (err) {
        bildir(err.message);
        if (ayarlar) ayarlariFormaYaz(); // eski değerlere dön
        throw err;
      });
  }

  document.querySelectorAll('input[name="tema"]').forEach(function (r) {
    r.addEventListener('change', function () {
      temayiUygula(r.value);
      ayarKaydet({ tema: r.value }).catch(function () {});
    });
  });

  document.querySelectorAll('input[type="checkbox"][data-ayar]').forEach(function (kutu) {
    kutu.addEventListener('change', function () {
      var d = {};
      d[kutu.dataset.ayar] = kutu.checked;
      ayarKaydet(d).catch(function () {});
    });
  });

  var adGirdisi = document.querySelector('[data-ayar="kullaniciAdi"]');
  adGirdisi.addEventListener('change', function () {
    var ad = adGirdisi.value.trim();
    if (!ad) {
      adGirdisi.value = ayarlar ? ayarlar.kullaniciAdi : 'Misafir';
      return;
    }
    ayarKaydet({ kullaniciAdi: ad }).then(function () { bildir('Kullanıcı adı kaydedildi'); }, function () {});
  });
  adGirdisi.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') adGirdisi.blur();
  });

  // ---------------- Başlangıç ----------------

  oynaticiyiKur();

  var ilkSorgu = new URL(location.href).searchParams.get('q');
  if (ilkSorgu === null) {
    try { ilkSorgu = localStorage.getItem('sonArama'); } catch (e) {}
  }
  ilkSorgu = (ilkSorgu || '').trim();
  aramaKutusu.value = ilkSorgu;

  api('/ayarlar')
    .then(function (a) {
      ayarlar = a;
      ayarlariFormaYaz();
      return gecmisiYukle();
    })
    .then(function () {
      // Son izlenen videoyu kaldığı yerden hazırla (otomatik başlatmaz).
      if (gecmis.length && !aktif) videoAc(gecmis[0]);
    })
    .catch(function () {
      bildir('Sunucuya bağlanılamadı');
      gecmisiCiz();
    })
    .then(function () {
      // Sayfa açılışındaki aramada link videoyu kendiliğinden açmaz, listede gösterir.
      if (ilkSorgu) aramaYap(ilkSorgu);
      else sonuclariCiz();
    });
})();
