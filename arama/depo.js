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

// Geçmişte yalnızca gerçek YouTube video kimlikleri tutulur.
const VIDEO_KIMLIGI = /^[A-Za-z0-9_-]{11}$/;

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
        // Eski sürümün örnek kayıtları (v1, v2...) gerçek video olmadığı için atılır.
        gecmis: (Array.isArray(ham.gecmis) ? ham.gecmis : []).filter(
          (k) => k && typeof k.id === 'string' && VIDEO_KIMLIGI.test(k.id) && typeof k.izlenme === 'string'
        ),
        ayarlar: { ...VARSAYILAN_AYARLAR, ...(ham.ayarlar || {}) },
      };
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
      this.veri = { gecmis: [], ayarlar: { ...VARSAYILAN_AYARLAR } };
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

  // tasi=false ise kayıt güncellenir ama izlenme zamanı (sırası) değişmez.
  async gecmiseEkle(kayit, { tasi = true } = {}) {
    const liste = this.veri.gecmis;
    const i = liste.findIndex((k) => k.id === kayit.id);
    const eski = i >= 0 ? liste[i] : {};
    const yeni = { ...eski, ...kayit };
    if (tasi || !yeni.izlenme) yeni.izlenme = new Date().toISOString();
    if (yeni.ilerleme === undefined) yeni.ilerleme = 0;
    if (yeni.konum === undefined) yeni.konum = 0;
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

module.exports = { Depo, VARSAYILAN_AYARLAR, VIDEO_KIMLIGI };
