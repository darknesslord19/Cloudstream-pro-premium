# CloudStream HTML API (html-api dalı)

Uygulama açıldığında yerel ağda `8686` portunda küçük bir sunucu başlar ve açılışta adresi gösterir (ör. `http://192.168.1.25:8686`).

## Kullanım

1. **CloudStreamHTML.apk** dosyasını kur. Asıl "CloudStream Dark" uygulamasının yanına ayrı olarak kurulur ve onu silmez. Depo ve eklentileri bu kopyaya bir kez eklemen gerekir.
2. Uygulamayı aç, ekranda görünen adresi not al.
3. Aynı Wi-Fi'daki herhangi bir cihazda:
   - Tarayıcıya adresi yaz: arayüzü uygulama kendisi sunar, **ya da**
   - `CloudStream.html` dosyasını aç ve istediğinde adresi gir.

## Uç noktalar

| Adres | Ne verir |
|---|---|
| `/api/providers` | Kurulu eklentiler |
| `/api/home?provider=` | Eklentinin ana sayfa listeleri |
| `/api/search?q=&provider=` | Arama (provider boşsa tüm eklentiler) |
| `/api/load?provider=&url=` | Detay, sezonlar ve bölümler |
| `/api/links?provider=&data=` | Video bağlantıları ve altyazılar |
| `/proxy?u=&h=` | Akışı Referer gibi başlıkları ekleyerek aktarır; HLS listelerini yeniden yazar |

Kod: `app/src/main/java/com/lagradost/cloudstream3/htmlapi/HtmlApiServer.kt`, arayüz: `app/src/main/assets/htmlapi/index.html`.

## Not

Sunucunun parolası yok. Uygulama açıkken aynı ağdaki herkes bu adrese erişebilir, bu yüzden yalnızca güvendiğin ağlarda kullan.
