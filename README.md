# HID Şifre Yazıcı (prototip, sürüm 0.2)

Telefon, bilgisayara Bluetooth klavye gibi bağlanır. Tarayıcıda bir giriş sayfası açılınca eklenti siteyi algılar,
telefon parmak izi ister ve doğrulanınca kullanıcı adı/şifreyi yazar. Telefon uzaklaşınca bilgisayar kilitlenir.

> Sadece deneme amaçlıdır. Gerçek hesaplar yerine test hesaplarıyla dene.

## Parçalar
| Parça | Klasör | Görevi |
|---|---|---|
| Android uygulaması | `app/` | Klavye olarak yazar, kayıtları şifreli tutar, BLE ile ajanla konuşur |
| Bilgisayar ajanı | `pc_agent/` | Uzaklaşınca kilitler, eklentiden gelen isteği telefona iletir |
| Chrome eklentisi | `chrome_extension/` | Giriş formunu algılar, site adını ajana bildirir |

Akış: Eklenti site adını ajana yollar → ajan imzalı komutu telefona BLE ile iletir → telefon kaydı eşleştirir →
parmak izi → telefon, klavye olarak şifreyi yazar.

## 1. Telefon (Android 11+)
1. APK'yı kur (GitHub Actions çıktısı), açılışta Bluetooth/yakındaki cihazlar/bildirim izinlerini ver.
2. Pil kısıtlamasından muaf tut: Ayarlar → Uygulamalar → HID Şifre → Pil → "Kısıtlama yok".
3. (İsteğe bağlı, daha hızlı) Ayarlar → Uygulamalar → Özel erişim → "Tam ekran bildirimleri" → HID Şifre'ye izin ver.
   Bu açıksa giriş isteği ekran kilitliyken bile doğrudan parmak izi penceresiyle açılır.

## 2. Telefonu klavye olarak eşleştir (bir kere)
1. Uygulamada **1) Telefonu görünür yap**, bilgisayarda Bluetooth → Cihaz ekle → telefonu seç, kodu onayla.
2. **2) Eşleşmiş bilgisayara bağlan** ile bilgisayarı seç. Sonrasında uygulama son bilgisayara kendiliğinden yeniden bağlanır.
3. Windows'ta klavye düzenini **ABD (ENG US)** yap (Win + Boşluk). Türkçe Q'da `@ | \` gibi karakterler yanlış çıkar.

## 3. Bilgisayar ajanı
```
cd pc_agent
pip install -r requirements.txt
python agent.py
```
İlk çalışmada `agent_config.json` oluşur ve ekranda **Eklenti TOKEN** yazar.
1. Telefonda **PC eşleştirme anahtarını göster** (parmak izi ister) → Kopyala → `agent_config.json` içindeki
   `phone_key_hex` alanına yapıştır → ajanı yeniden başlat.
2. Telefonda "Arka plan servisi" kutusu açık olmalı. Ajan birkaç saniye içinde "Kilit koruması aktif" yazar.

Kilit ayarları (`agent_config.json`):
- `rssi_threshold`: -80 varsayılan. Çok kolay kilitleniyorsa -85/-90 yap, geç kilitleniyorsa -75.
- `debug: true` yaparsan ajan RSSI değerlerini yazar, eşiği buna bakarak ayarla.
- `lock_enabled: false` kilidi kapatır, otomatik giriş çalışmaya devam eder.

## 4. Chrome eklentisi
1. `chrome_extension/config.js` içindeki `TOKEN` değerini ajanın yazdığı Eklenti TOKEN ile değiştir.
2. Chrome → `chrome://extensions` → Geliştirici modu → **Paketlenmemiş öğe yükle** → `chrome_extension` klasörü.

## 5. Kayıt ekle ve dene
1. Uygulamada **+ Kayıt ekle**: Ad (Gmail), Site (`google.com`, alt alan adlarını da kapsar), kullanıcı adı, şifre.
2. Tarayıcıda giriş sayfasını aç. Telefona bildirim gelir (uygulama ekrandaysa direkt parmak izi), parmak izini okut.
3. İki adımlı sayfalarda (Gmail) ilk adımdan sonra ikinci parmak izi istenmez: çözülen bilgi 45 saniye bellekte tutulur.

## Güvenlik notları
- Komutlar HMAC-SHA256 ile imzalanır, 60 sn'den eski veya tekrar eden komutlar reddedilir. Anahtar telefonda Keystore ile sarılıdır.
- Site eşleştirmesi tam alan adı veya alt alan adıdır: `google.com.evil.com` ve `evilgoogle.com` eşleşmez. Eklenti sadece https sayfaları bildirir.
- Ajanın yerel adresi token ister ve sadece eklenti kaynağından gelen istekleri kabul eder.
- Şifre, klavye tuş vuruşu olarak Bluetooth bağlantısının şifrelemesiyle gider. Odaktaki pencereye yazıldığı için yanlış pencere riski vardır.
- Uzaklaşınca kilit sadece kilitler, kilit açmaz. Aynı uygulamayı çalıştıran başka bir telefon yakındaysa kilidi engelleyebilir.
- Yeni parmak izi eklersen Keystore anahtarı geçersiz olur: **Tüm kayıtları sıfırla** ile temizle, kayıtları yeniden ekle.
- Komut içindeki site adı havadan şifresiz gider (kimlik bilgisi değil).

## Bilinen sınırlar
- Klasik Bluetooth HID kullanır (BLE değil). Bazı telefonlarda HID device profili kapalıdır.
- Her site için tek kayıt (aynı siteye ikinci kayıt öncekinin yerine geçer).
- Karakter eşleme ABD düzenine göre.
- Ajan Windows için test edilecek şekilde yazıldı; Linux/macOS'ta kilit komutu farklı çalışabilir.
