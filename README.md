# Audio SA201 Kapi Acici

Bu proje, `ESP8266 2 kanal 5V ESP-01 WiFi role modulu` ile `Audio SA201` diafonunun kapı açma butonunu uzaktan tetiklemek için iki parçadan oluşur:

- `firmware/audio_sa201_esp8266/audio_sa201_esp8266.ino`: ESP-01 üstüne yüklenecek firmware
- `android/`: Android uygulaması ve ana ekran widget'i

## Nasil Calisir

Bu role kartinda roleleri dogrudan ESP8266 GPIO'lari degil, kart uzerindeki 8-bit MCU surer. ESP-01 firmware'i role kartinin MCU'suna `115200` baud UART uzerinden hex komutlar yollar:

- 1. role ac: `A0 01 01 A2`
- 1. role kapa: `A0 01 00 A1`
- 2. role ac: `A0 02 01 A3`
- 2. role kapa: `A0 02 00 A2`

Baslangicta `Role 1` icin `SA201` icindeki kapı acma butonunun iki ucuna paralel baglanti onerilir:

- `COM` -> butonun bir ucu
- `NO` -> butonun diger ucu

`NC` kullanmayin.

## Donanim Notlari

- Besleme: `5V 1A` minimum, `5V 2A` onerilir
- Kartta `2 role` de firmware ve uygulama tarafinda aktif olarak desteklenir
- `Role 1` tipik olarak kapı acma icin kullanilir
- `Role 2` ileride baska gorev icin isim/mod atayarak kullanilabilir
- Diafon hatti ile yalnizca role kontagi temas etsin
- ESP beslemesini diafon hattindan alma; role kartini kendi `5V` adaptoru ile besle

## Firmware Ozellikleri

- Kayitli Wi-Fi'ya baglanmayi dener
- Baglanamazsa `KapiAc-XXXXXX` adli kendi erisim noktasini acar
- Yerel web paneli sunar
- `POST /api/provision` ile AP modunda ilk Wi-Fi kurulumu alabilir
- `POST /api/channel` ile iki role de kontrol edilebilir
- `GET /api/status` ile durum dondurur
- Token korumasi vardir
- Kanal basina ayri:
  - etiket
  - mod: `momentary` veya `latch`
  - darbe suresi
  - mevcut durum

Ilk acilista varsayilan token:

- `door-<chipid>`

Web panelinden degistirebilirsiniz.

## Android Uygulamasi

Uygulama sunlari yapar:

- Ilk kurulumda cihaz AP'sine baglanmaya yardim eder
- Ev Wi-Fi bilgisini APK icinden cihaza gonderebilir
- Cihaz adresi ve token kaydeder
- Kanal etiketlerini, modlarini ve surelerini kaydeder
- Cihaz ayarlarini ESP'ye gonderebilir
- Cihazdan mevcut durumu geri okuyabilir
- Iki roleyi de uygulamadan ayri ayri calistirir
- Iki farkli widget sunar:
  - tek butonlu widget: secilen birincil kanal
  - iki butonlu widget: iki roleyi ayni anda gosterir

Uygulamada cihaz adresi ornekleri:

- Ev aginda: `http://192.168.1.50`
- Cihaz AP modundayken: `http://192.168.4.1`

## APK ile Ilk Kurulum

1. Cihazi ilk kez acin; AP modunda `KapiAc-XXXXXX` agini acacak
2. Uygulamada `Cihaz AP SSID` alanina bu adi yazin
3. `Ev Wi-Fi adi` alaninda mevcut bagli agi dropdown'dan secin
4. Gerekirse `Wi-Fi Listesini Yenile` ile yakin aglari listeleyin
5. `Ev Wi-Fi sifresi`ni girin
6. `Cihaz AP'ye Baglan` deyin
7. Android baglanti onayi isterse kabul edin
8. `Ev Wi-Fi Bilgilerini Cihaza Yaz` deyin
9. Cihaz yeniden baslar ve ev agina gecmeye calisir

Not:

- Android 10 ve uzeri surumlerde uygulama `WifiNetworkSpecifier` ile AP baglantisi isteyebilir
- `Wi-Fi` tarama listesi icin Android'in resmi `getScanResults()` akisi kullanilir ve `ACCESS_FINE_LOCATION` izni gerekir
- Android 9 ve alti icin AP baglantisini ayarlardan manuel yapip yine ayni provisioning adimini kullanin

## Android Studio ile Acma

1. `android/` klasorunu Android Studio ile acin
2. Gerekirse SDK ve Gradle bilesenlerini kurun
3. Uygulamayi telefona yukleyin
4. Widget'i ana ekrana ekleyin

Bu makinede Android SDK/Gradle tam kurulu olmadigi icin burada APK derlemesi yapilmadi.

## Arduino IDE ile Yukleme

1. Arduino IDE'ye `ESP8266` board paketini kurun
2. `Generic ESP8266 Module` veya kullandiginiz `ESP-01/ESP-01S` profiline yakin karti secin
3. Flash boyutu ve seri hiz ayarlarinizi modulinize gore yapin
4. `firmware/audio_sa201_esp8266/audio_sa201_esp8266.ino` dosyasini yukleyin

Not:

- Role kartinin uzerindeki MCU ile haberlesme icin firmware `Serial` portunu kullanir
- ESP-01'i role kartindan sokup ayri programlamak genelde daha kolaydir
- `momentary` modda role secilen sure kadar ceker ve birakir
- `latch` modda buton her basista role durumunu ac/kapa toggle yapar
