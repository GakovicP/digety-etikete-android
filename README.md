# NetPlanet link — Android aplikacija za Digety etikete

Vezivanje etiketa za proizvode skeniranjem, mapa etiketa u prodavnici, GPS akcija, alarmi, prijave problema i izveštaji.
Radi direktno sa **Digety System API** (isti nalog kao Digety web dashboard).

## Instalacija
1. Otvori **Releases** (desno na stranici repozitorijuma) → najnovija verzija → preuzmi `NetPlanet-link-1.0.X.apk`.
2. Na telefonu otvori fajl i dozvoli „instalaciju iz nepoznatih izvora“ za browser / fajl menadžer.
3. Nove verzije se instaliraju preko stare (isti potpis), podaci ostaju.

Svaki `push` na `main` automatski pravi novu verziju (GitHub Actions → *Build APK*).

## Ekrani
| Ekran | Šta radi |
|---|---|
| **Veži** | Skeniraj etiketu → vidi šta je trenutno na njoj (slika, proizvod, cena, baterija) → skeniraj barkod proizvoda → *Poveži*. Neprekidni režim: posle uspeha odmah skenira sledeću. LED treptanje, odvezivanje, prijava. Radi i sa ručnim skenerima (Zebra, iData…) koji kucaju kod + Enter. |
| **Mapa → Prodavnica** | Mapa sprata iz Digety-ja sa zonama, pozicija izabrane etikete (osvežava se na 5 s), sve etikete (na 15 s), mesto alarma. Radi samo u prodavnicama sa BlueIoT lokalizacijom. |
| **Mapa → GPS** | OpenStreetMap sa GPS tačkama svih akcija sa telefona (ko je, kad i gde vezivao/prijavljivao). |
| **Alarmi** | Digety alarmi prodavnice (geofence, tamper, nestale etikete), osvežavanje na 30 s, notifikacija na telefonu za nove alarme (provera na 15 min i u pozadini). |
| **Prijave** | Digety „issues“: nova prijava sa kategorijom, fotografijom i GPS-om; lista sa dijagnostikom etikete, komentari, rešavanje. |
| **Izveštaj** | Vezivanje sa telefona (po danu, radniku, greške, CSV), etikete sa slabom baterijom, offline etikete (CSV). |

## Napomene
- Prijava: Cognito `USER_PASSWORD_AUTH`, čuva se samo refresh token (lozinka se ne pamti).
- Izveštaj o vezivanju se čuva lokalno na telefonu (fajl `istorija.json`); CSV se deli preko Android menija.
- Pozicije na mapi: Digety ne dokumentuje kako se povezati na `liveLocations` pretplatu, pa aplikacija periodično čita `latest_location`.
