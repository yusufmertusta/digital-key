# HID Şifre Yazıcı (prototip, sürüm 0.3)

Telefon, bilgisayara Bluetooth klavye gibi bağlanır. Tarayıcıda giriş sayfası açılınca eklenti siteyi algılar,
telefon parmak izi ister ve kullanıcı adı/şifreyi yazar. Telefon uzaklaşınca bilgisayar kilitlenir,
yaklaşınca telefon parmak iziyle Windows PIN'ini yazıp kilidi açar.

> Sadece deneme amaçlıdır. Gerçek hesaplar yerine test hesaplarıyla dene. Kişisel bilgisayarında dene.

## 0.3'te yenilenenler
- **Türkçe Q klavye** (varsayılan). ABD düzeni de seçilebilir. **Klavye testi** düğmesi ile doğrula.
- **Hızlı kilit**: kilit mesafesi aşılınca ~1-2 sn içinde kilitlenir. **Mesafe kalibrasyonu** (`--calibrate`).
- **Oturum açma**: Windows PIN'i telefonda parmak izine bağlı saklanır. Yaklaşınca popup, parmak izi, PIN yazılır.
- **Popup (kart) penceresi**: giriş ve oturum açma istekleri ekranda kart olarak açılır (titreşim + ekranı uyandırma).
- İsteğe bağlı **hareket algısı**: telefon hareket ederken ajan daha hızlı kilitler.
- Gecikme kaydı: ajan "eşiği aşmaktan kilide kaç sn geçti" bilgisini yazar.

## Parçalar
| Parça | Klasör | Görevi |
|---|---|---|
| Android uygulaması | `app/` | Klavye olarak yazar, kayıtları şifreli tutar, BLE ile ajanla konuşur |
| Bilgisayar ajanı | `pc_agent/` | Uzaklaşınca kilitler, kilit durumunu telefona bildirir, eklenti isteğini iletir |
| Chrome eklentisi | `chrome_extension/` | Giriş formunu algılar, site adını ajana bildirir |

## 1. Telefon (Samsung Galaxy S21, Android 11+)
1. APK'yı kur (GitHub Actions → Artifacts), açılışta Bluetooth / yakındaki cihazlar / bildirim izinlerini ver.
2. Uygulamada **"Popup izni: diğer uygulamaların üzerinde göster"** düğmesine bas ve izni aç.
   Bu izin olmadan arka plandayken popup açılmaz, sadece bildirim gelir.
3. **"Pil kısıtlamasını kaldır"** düğmesine bas. Samsung ayrıca: Ayarlar → Pil → Arka plan kullanım sınırları →
   "Uyutulmayan uygulamalar" listesine HID Şifre'yi ekle.
4. (İsteğe bağlı) Ayarlar → Uygulamalar → Özel erişim → "Tam ekran bildirimleri" → HID Şifre'ye izin ver.

## 2. Telefonu klavye olarak eşleştir (bir kere)
1. **1) Telefonu görünür yap**, bilgisayarda Bluetooth → Cihaz ekle → telefonu seç, kodu onayla.
2. **2) Eşleşmiş bilgisayara bağlan** ile bilgisayarı seç. Sonrasında uygulama son bilgisayara kendiliğinden yeniden bağlanır.
3. Windows'ta klavye düzeni **Türkçe Q** olmalı, uygulamada **Klavye** bölümünde "Türkçe Q" seçili olmalı.

### Klavye testi
Uygulamada **Klavye testi** düğmesine bas, 5 sn içinde bilgisayarda Not Defteri'ni aç ve imleci içine koy.
Yazılması gereken:
```
ığüşöçİĞÜŞÖÇ 1234567890 !'^+%&/()=?_-*,;:.<>|@€₺#${[]}\"~
```
- `^` veya `~` yanında fazladan boşluk çıkıyor ya da eksik kalıyorsa **"^ ve ~ sonrası Boşluk gönder"** kutusunu değiştir.
- `@ | € { [ ] } \ ~` çıkmıyorsa Windows'ta sağ Alt (AltGr) tuşu farklı çalışıyor demektir; bana bildir.
- Harfler kayıyorsa (ı/i karışıyorsa) Windows düzeni Türkçe **Q** değil **F** olabilir.

## 3. Bilgisayar ajanı
```
cd pc_agent
pip install -r requirements.txt
python agent.py
```
Ajanı kendin başlatırsın (otomatik başlatma yok). İlk çalışmada `agent_config.json` oluşur ve ekranda **Eklenti TOKEN** yazar.
1. Telefonda **PC eşleştirme anahtarını göster** (parmak izi ister) → Kopyala → `agent_config.json` içindeki
   `phone_key_hex` alanına yapıştır → ajanı yeniden başlat.
2. Telefonda "Arka plan servisi" kutusu açık olmalı. Ajan birkaç saniye içinde "Kilit koruması aktif" yazar.

### Mesafe kalibrasyonu (önerilir)
```
python agent.py --calibrate
```
1. Telefonla bilgisayarın başında otur, Enter.
2. Kilitlenmesini istediğin mesafeye git (masadan kalkınca +1-2 m), telefonu normal tuttuğun gibi tut, Enter.
3. Ajan `lock_rssi` ve `near_rssi` değerlerini `agent_config.json`'a yazar.

Not: Bluetooth sinyal gücü mesafeyi ±2-3 m belirsizlikle verir (vücut, cep, duvar etkiler). Bu telefon ve bilgisayarda
Channel Sounding yok; gerçek ölçüm için Bluetooth 6.0 donanım gerekir. Kalibrasyonu telefonu her zamanki yerde (cepte) tutarak yap.

### Ayarlar (`agent_config.json`)
- `sensitivity`: `fast` (varsayılan), `normal`, `slow`. Hızlı modda ~1,5 sn'lik pencerede örneklerin %60'ı eşiğin altındaysa kilitler.
- `lock_rssi`: bundan zayıf sinyal "kilit mesafesinin ötesi". `near_rssi`: kilitliyken bundan güçlü sinyal "yaklaştı".
- `debug: true`: RSSI, kilit durumu ve hareket bilgisini yazar.
- `lock_enabled: false`: otomatik kilidi kapatır. `unlock_support: false`: telefona kilit bildirmeyi kapatır.
- Kilit kararı sonrası konsolda **"eşiği aşmaktan kilide: X sn"** gecikmesi yazılır.

Çok kolay kilitleniyorsa `lock_rssi` değerini 3-5 düşür, geç kilitleniyorsa yükselt. Yanlış kilitlenme çoksa `sensitivity` = `normal`.

## 4. Chrome eklentisi
1. `chrome_extension/config.js` içindeki `TOKEN` değerini ajanın yazdığı Eklenti TOKEN ile değiştir.
2. Chrome → `chrome://extensions` → Geliştirici modu → **Paketlenmemiş öğe yükle** → `chrome_extension` klasörü.

## 5. Site kaydı ve deneme
1. Uygulamada **+ Kayıt ekle**: Ad (Gmail), Site (`google.com`, alt alan adlarını da kapsar), kullanıcı adı, şifre.
2. Tarayıcıda giriş sayfasını aç. Telefonda popup açılır, parmak izini okut.
3. İki adımlı sayfalarda (Gmail) ikinci parmak izi istenmez: çözülen bilgi 45 saniye bellekte tutulur.

## 6. Oturum açma (Windows PIN)
1. Uygulamada **Windows PIN kaydet / değiştir**: PIN'i yaz, parmak iziyle kaydet. (Rakamdan oluşan PIN çoğunlukla Enter istemez.)
2. Ajan çalışırken bilgisayarı kilitle:
   - **Uzaklaşıp kilitlendiyse ve geri geldiysen**: eşiğe ilk girişinde telefonda popup açılır → parmak izi → PIN yazılır.
     Popup açılana kadar veya bildirimi silene kadar geçerlidir; yanında sürekli parmak izi istemez.
   - **Elle kilitlediysen (uzaklaşmadan)**: popup çıkmaz, küçük kalıcı bir **"Kilidi aç"** bildirimi gelir.
     Dokunup parmak iziyle açabilirsin. Uzaklaşıp dönersen normal popup gelir.
3. **Kilit ekranı uyandırma tuşu** (varsayılan Shift): PIN'den önce gönderilir. Windows PIN kutusu açılmıyorsa
   Ctrl / Esc / Boşluk dene. PIN'in ilk rakamı kayboluyorsa bekleme süresi için bana bildir (şu an 1 sn).

### Güvenlik önlemleri
- PIN yazılmadan hemen önce ajanın "hâlâ kilitli" bilgisi taze mi kontrol edilir (ajan her ~8 sn bildirir, 25 sn geçerli).
  Bilgisayar zaten açıksa PIN yazılmaz (rastgele pencereye PIN düşmez).
- Otomatik yeniden deneme yok. PIN yazıldıktan sonra bilgisayar hâlâ kilitliyse bir uyarı çıkar;
  üst üste 2 başarısızlıkta 5 dakika otomatik açma kapanır (Windows hesabını kilitlememek için).

## Güvenlik notları
- Komutlar HMAC-SHA256 ile imzalanır, 60 sn'den eski veya tekrar eden komutlar reddedilir. Anahtar telefonda Keystore ile sarılıdır.
- Site eşleştirmesi tam alan adı veya alt alan adıdır: `google.com.evil.com` ve `evilgoogle.com` eşleşmez. Eklenti sadece https sayfaları bildirir.
- Ajanın yerel adresi token ister ve sadece eklenti kaynağından gelen istekleri kabul eder.
- Şifre ve PIN, klavye tuş vuruşu olarak Bluetooth bağlantısının şifrelemesiyle gider. Odaktaki pencereye yazıldığı için yanlış pencere riski vardır.
- Telefon parmak izi eklenirse Keystore anahtarı geçersiz olur: **Tüm kayıtları sıfırla** ile temizle, kayıtları ve PIN'i yeniden ekle.
- Telefon yakında ve parmak izi sende olduğu sürece bilgisayar açılabilir; telefonunu kaybedersen PIN'i değiştir.
- Komut içindeki bilgisayar/site adı havadan şifresiz gider (kimlik bilgisi değil).

## Bilinen sınırlar ve denenecekler
- Klasik Bluetooth HID kullanır (BLE değil). Bazı telefonlarda HID device profili kapalıdır.
- Windows kilit ekranı Bluetooth klavyeyi kabul etmeli; bağlantı kilitliyken düşerse PIN yazılamaz. İlk denemede gözle.
- Windows UAC / güvenli masaüstü kısa süre "kilitli" gibi görünür; ajan bunu ~1 sn doğrulama ile eler.
- Hareket algısı ekran kapalıyken telefonun işlemcisi uyuyorsa gecikebilir; varsayılan kapalı.
- Her site için tek kayıt (aynı siteye ikinci kayıt öncekinin yerine geçer).
- Ajan sadece Windows için kilit durumu algılar; Linux/macOS'ta oturum açma desteği çalışmaz.
- Android kodu bu sürümde derleme sunucusunda (Actions) ilk kez derlenecek; hata olursa log'u gönder.
