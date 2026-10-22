# HID Şifre Yazıcı (prototip)

Android telefonu Bluetooth klavye gibi bilgisayara tanıtır. Biyometrik doğrulamadan sonra kayıtlı kullanıcı adı ve şifreyi bilgisayara "yazar".

> Sadece deneme amaçlıdır. Gerçek hesaplarını değil test hesaplarını kullan.

## Gereksinimler
- Android Studio (Koala veya yeni), Android 11+ (API 30) fiziksel telefon (emülatör Bluetooth HID desteklemez)
- Telefonda kayıtlı parmak izi/yüz (güçlü biyometrik)
- Bilgisayarda Bluetooth

## Kurulum
1. Android Studio ile `HidPasswordTyper` klasörünü aç, Gradle sync bitsin.
2. Telefonu USB ile bağla (geliştirici modu + USB hata ayıklama), Run.

## Kullanım
1. Uygulamayı aç, Bluetooth iznini ver. Durum satırında "Klavye olarak kayıtlı" yazmalı.
2. **Telefonu görünür yap** butonuna bas.
3. Bilgisayarda Bluetooth ayarlarından "Cihaz ekle" ile telefonu bul ve eşleştir (klavye olarak görünür).
4. Uygulamada **Eşleşmiş bilgisayara bağlan** ile bilgisayarı seç. Durum "Bağlı" olmalı.
5. Kullanıcı adı/şifre gir, **Kaydet** (parmak izi). Şifre Keystore anahtarıyla şifrelenir.
6. Bilgisayarda bir giriş alanına tıkla, telefonda **Bilgisayara yaz** (parmak izi). Metin yazılır.

## Bilinen sınırlar
- Klasik Bluetooth HID kullanır (BLE değil). Kullanıcı açısından fark yok.
- Bazı üreticiler (özellikle bazı Samsung/Xiaomi sürümleri) HID device profilini kapatmış olabilir; "registerApp başarısız" görürsen cihaz desteklemiyordur.
- Karakter eşleme ABD (US) klavye düzenine göre. Bilgisayarda Türkçe Q düzeni seçiliyse `@`, `|`, `\` gibi karakterler yanlış çıkar. Test için bilgisayarın giriş yöntemini US yap.
- Yeni parmak izi eklenirse anahtar geçersiz olur; kaydı silip yeniden kaydet.
- Şifre yazılırken kısa süre bellekte düz metin olarak durur.
