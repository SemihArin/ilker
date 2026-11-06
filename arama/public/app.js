(function () {
  'use strict';

  var ayarlar = null;
  var gecmis = [];

  // ---------------- Yardımcılar ----------------

  function api(yol, secenek) {
    secenek = secenek || {};
    var basliklar = {};
    if (secenek.govde !== undefined) basliklar['Content-Type'] = 'application/json';
    return fetch('/api' + yol, {
      method: secenek.yontem || 'GET',
      headers: basliklar,
      body: secenek.govde !== undefined ? JSON.stringify(secenek.govde) : undefined,
    }).then(function (yanit) {
      if (yanit.status === 204) return null;
      return yanit.json().catch(function () { return {}; }).then(function (veri) {
        if (!yanit.ok) throw new Error(veri.hata || 'İstek başarısız (' + yanit.status + ')');
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

  var toastZamani;
  function bildir(mesaj) {
    var t = document.getElementById('toast');
    t.textContent = mesaj;
    t.classList.add('show');
    clearTimeout(toastZamani);
    toastZamani = setTimeout(function () { t.classList.remove('show'); }, 2200);
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
    return new Date(iso).toLocaleDateString('tr-TR');
  }

  function grupAdi(iso) {
    var d = new Date(iso);
    var bugun = new Date();
    bugun.setHours(0, 0, 0, 0);
    var gunFarki = Math.floor((bugun - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
    if (gunFarki <= 0) return 'Bugün';
    if (gunFarki === 1) return 'Dün';
    if (gunFarki < 7) return 'Bu hafta';
    return 'Daha eski';
  }

  function ilerlemeCubugu(sinif, yuzde) {
    var bar = el('div', sinif);
    var ic = el('span');
    ic.style.setProperty('--p', yuzde + '%');
    bar.appendChild(ic);
    return bar;
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

  // ---------------- Geçmiş ----------------

  function gecmisSatiri(k, silinebilir) {
    var satir = el('div', 'result');
    satir.dataset.id = k.id;
    var thumb = el('div', 'thumb');
    if (k.sure) thumb.appendChild(el('span', 'duration', k.sure));
    if (devamGoster() && k.ilerleme > 0) thumb.appendChild(ilerlemeCubugu('thumb-bar', k.ilerleme));
    satir.appendChild(thumb);
    var bilgi = el('div');
    bilgi.appendChild(el('h3', 'result-title', k.baslik));
    bilgi.appendChild(el('div', 'result-channel', (k.kanal ? k.kanal + ' · ' : '') + goreliZaman(k.izlenme)));
    satir.appendChild(bilgi);
    if (silinebilir) {
      var sil = el('button', 'remove', '×');
      sil.type = 'button';
      sil.title = 'Geçmişten kaldır';
      sil.setAttribute('aria-label', 'Geçmişten kaldır: ' + k.baslik);
      satir.appendChild(sil);
    }
    return satir;
  }

  function gecmisSayfasiniCiz() {
    var kap = document.getElementById('gecmis-listesi');
    kap.textContent = '';
    document.getElementById('gecmisi-temizle').hidden = gecmis.length === 0;
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
      liste.appendChild(gecmisSatiri(k, true));
    });
  }

  function kartlariCiz() {
    var kap = document.getElementById('son-izlenenler');
    kap.textContent = '';
    if (!gecmis.length) {
      kap.appendChild(el('div', 'empty', 'İzlediğin videolar burada görünecek.'));
      return;
    }
    gecmis.slice(0, 10).forEach(function (k) {
      var kart = el('div', 'card');
      kart.dataset.id = k.id;
      kart.appendChild(el('div', 'card-img'));
      var govde = el('div', 'card-body');
      govde.appendChild(el('h3', 'card-title', k.baslik));
      if (devamGoster()) govde.appendChild(ilerlemeCubugu('bar', k.ilerleme || 0));
      kart.appendChild(govde);
      kap.appendChild(kart);
    });
  }

  // Ana sayfadaki sonuçlarda kaldığın yeri göster.
  function sonuclariGuncelle() {
    var harita = {};
    gecmis.forEach(function (k) { harita[k.id] = k; });
    document.querySelectorAll('#sonuclar .result').forEach(function (satir) {
      var thumb = satir.querySelector('.thumb');
      var eski = thumb.querySelector('.thumb-bar');
      if (eski) eski.remove();
      var k = harita[satir.dataset.id];
      if (devamGoster() && k && k.ilerleme > 0) thumb.appendChild(ilerlemeCubugu('thumb-bar', k.ilerleme));
    });
  }

  function hepsiniCiz() {
    gecmisSayfasiniCiz();
    kartlariCiz();
    sonuclariGuncelle();
  }

  function gecmisiYukle() {
    return api('/gecmis').then(function (liste) {
      gecmis = liste;
      hepsiniCiz();
    });
  }

  function satirdanVideo(satir) {
    return {
      id: satir.dataset.id,
      baslik: satir.querySelector('.result-title, .card-title').textContent,
      kanal: (satir.querySelector('.result-channel') || {}).textContent,
      sure: (satir.querySelector('.duration') || {}).textContent,
    };
  }

  function videoAc(video) {
    // Kanal metni geçmiş satırından geldiyse " · 2 saat önce" kısmını at.
    if (video.kanal) video.kanal = video.kanal.split(' · ')[0];
    var kayitli = gecmis.find(function (k) { return k.id === video.id; });
    if (kayitli) {
      video.kanal = video.kanal || kayitli.kanal;
      video.sure = video.sure || kayitli.sure;
    }

    var iframe = document.querySelector('.video iframe');
    if (ayarlar && ayarlar.otomatikOynat) {
      var url = new URL(iframe.src);
      url.searchParams.set('autoplay', '1');
      iframe.src = url.toString();
    }
    goster('home');

    if (ayarlar && !ayarlar.gecmisiKaydet) return;
    api('/gecmis', { yontem: 'POST', govde: video })
      .then(function () { return gecmisiYukle(); })
      .catch(function (err) { bildir(err.message); });
  }

  document.getElementById('sonuclar').addEventListener('click', function (e) {
    var satir = e.target.closest('.result');
    if (satir) videoAc(satirdanVideo(satir));
  });

  document.getElementById('son-izlenenler').addEventListener('click', function (e) {
    var kart = e.target.closest('.card');
    if (kart) videoAc(satirdanVideo(kart));
  });

  document.getElementById('gecmis-listesi').addEventListener('click', function (e) {
    var satir = e.target.closest('.result');
    if (!satir) return;
    if (e.target.closest('.remove')) {
      api('/gecmis/' + encodeURIComponent(satir.dataset.id), { yontem: 'DELETE' })
        .then(function () {
          bildir('Geçmişten kaldırıldı');
          return gecmisiYukle();
        })
        .catch(function (err) { bildir(err.message); });
      return;
    }
    videoAc(satirdanVideo(satir));
  });

  document.getElementById('gecmisi-temizle').addEventListener('click', function () {
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
        hepsiniCiz();
      })
      .catch(function (err) {
        bildir(err.message);
        if (ayarlar) ayarlariFormaYaz(); // eski değerlere dön
      });
  }

  document.querySelectorAll('input[name="tema"]').forEach(function (r) {
    r.addEventListener('change', function () {
      temayiUygula(r.value);
      ayarKaydet({ tema: r.value });
    });
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
    ayarKaydet({ kullaniciAdi: ad }).then(function () { bildir('Kullanıcı adı kaydedildi'); });
  });
  adGirdisi.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') adGirdisi.blur();
  });

  // ---------------- Başlangıç ----------------

  api('/ayarlar')
    .then(function (a) {
      ayarlar = a;
      ayarlariFormaYaz();
      return gecmisiYukle();
    })
    .catch(function () {
      bildir('Sunucuya bağlanılamadı');
      hepsiniCiz();
    });
})();
