'use strict';

// Geçmiş ve ayarları bir JSON dosyasında saklayan basit depo.
// Yazmalar sıraya alınır ve geçici dosya + rename ile atomik yapılır.

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');

const VARSAYILAN_AYARLAR = Object.freeze({
  tema: 'sistem', // 'sistem' | 'acik' | 'koyu'
  otomatikOynat: false,
  kaldigimYerdenDevam: true,
  gecmisiKaydet: true,
  davetleriKabulEt: true,
  kullaniciAdi: 'Misafir',
});

const SAAT = 60 * 60 * 1000;
const GUN = 24 * SAAT;

// İlk çalıştırmada sayfadaki örnek geçmişle aynı içerik.
function ornekGecmis() {
  const simdi = Date.now();
  const ornek = [
    ['3:42', 35, 2 * SAAT], ['12:05', 80, 5 * SAAT], ['8:19', 100, 9 * SAAT],
    ['45:30', 20, 1 * GUN], ['5:07', 60, 1 * GUN + SAAT], ['1:02:14', 45, 1 * GUN + 2 * SAAT],
    ['9:48', 90, 3 * GUN], ['15:33', 15, 4 * GUN], ['4:21', 70, 5 * GUN], ['27:56', 55, 6 * GUN],
  ];
  return ornek.map(([sure, ilerleme, once], i) => ({
    id: 'v' + (i + 1),
    baslik: 'Video başlığı ' + (i + 1),
    kanal: 'Kanal adı',
    sure,
    ilerleme,
    izlenme: new Date(simdi - once).toISOString(),
  }));
}

class Depo {
  constructor(dosya) {
    this.dosya = dosya;
    this.veri = null;
    this.kuyruk = Promise.resolve();
  }

  async yukle() {
    try {
      const ham = JSON.parse(await fsp.readFile(this.dosya, 'utf8'));
      this.veri = {
        gecmis: Array.isArray(ham.gecmis) ? ham.gecmis : [],
        ayarlar: { ...VARSAYILAN_AYARLAR, ...(ham.ayarlar || {}) },
      };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
      this.veri = { gecmis: ornekGecmis(), ayarlar: { ...VARSAYILAN_AYARLAR } };
      await this.kaydet();
    }
  }

  kaydet() {
    const icerik = JSON.stringify(this.veri, null, 2);
    this.kuyruk = this.kuyruk.then(async () => {
      await fsp.mkdir(path.dirname(this.dosya), { recursive: true });
      const gecici = this.dosya + '.tmp';
      await fsp.writeFile(gecici, icerik);
      await fsp.rename(gecici, this.dosya);
    });
    return this.kuyruk;
  }

  // ---- Geçmiş ----
  gecmis() {
    return [...this.veri.gecmis].sort((a, b) => b.izlenme.localeCompare(a.izlenme));
  }

  async gecmiseEkle(kayit) {
    const liste = this.veri.gecmis;
    const i = liste.findIndex((k) => k.id === kayit.id);
    const eski = i >= 0 ? liste[i] : {};
    const yeni = { ...eski, ...kayit, izlenme: new Date().toISOString() };
    if (yeni.ilerleme === undefined) yeni.ilerleme = 0;
    if (i >= 0) liste.splice(i, 1);
    liste.push(yeni);
    // Çok büyümesin: en yeni 200 kayıt.
    if (liste.length > 200) {
      this.veri.gecmis = this.gecmis().slice(0, 200);
    }
    await this.kaydet();
    return yeni;
  }

  async gecmistenSil(id) {
    const once = this.veri.gecmis.length;
    this.veri.gecmis = this.veri.gecmis.filter((k) => k.id !== id);
    if (this.veri.gecmis.length === once) return false;
    await this.kaydet();
    return true;
  }

  async gecmisiTemizle() {
    this.veri.gecmis = [];
    await this.kaydet();
  }

  // ---- Ayarlar ----
  ayarlar() {
    return { ...this.veri.ayarlar };
  }

  async ayarlariGuncelle(degisiklik) {
    this.veri.ayarlar = { ...this.veri.ayarlar, ...degisiklik };
    await this.kaydet();
    return this.ayarlar();
  }
}

module.exports = { Depo, VARSAYILAN_AYARLAR };
