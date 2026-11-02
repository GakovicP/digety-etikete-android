# NetPlanet link — Android + iOS aplikacija za Digety etikete

Vezivanje etiketa za proizvode skeniranjem, alarmi, prijave problema i izveštaji.
Radi direktno sa **Digety System API** (isti nalog kao Digety web dashboard).

**Jedan kod za obe platforme:** Kotlin Multiplatform + Compose Multiplatform.

## Struktura

| Folder | Šta je unutra |
|---|---|
| `composeApp/src/commonMain` | **Zajednički kod (~90%)**: svi ekrani, Digety API (Ktor), prijava, istorija, izveštaji |
| `composeApp/src/androidMain` | Android: skener (Google code scanner), GPS, kamera, deljenje, alarmi u pozadini (WorkManager) |
| `composeApp/src/iosMain` | iOS: most prema Swift kodu, podešavanja, fajlovi |
| `iosApp/` | iOS aplikacija (Swift): skener (AVFoundation), GPS (CoreLocation), kamera, deljenje; projekat se pravi iz `project.yml` (XcodeGen) |

Izmena u `commonMain` važi odmah za obe aplikacije.

## Build
Svaki `push` na `main` pokreće GitHub Actions (*Build Android + iOS*) i pravi **Release** sa:
- `NetPlanet-link-1.1.X.apk` — Android, instalira se direktno na telefon (preko stare verzije, podaci ostaju);
- `NetPlanet-link-1.1.X-iOS-simulator.zip` — iOS za simulator.

## Testiranje iOS verzije

**Bez Apple naloga (odmah):**
- **Appetize.io** — otvori nalog, *Upload* → izaberi `...-iOS-simulator.zip` → aplikacija radi u browseru kao iPhone. Kamera/GPS su simulirani (barkod se može ukucati ručno).
- **Mac sa Xcode-om** — raspakuj zip, pokreni simulator i prevuci `NetPlanetLink.app` na njega.

**Na pravom iPhone-u (potreban Apple Developer nalog, 99 USD/god.):**
TestFlight — build se potpisuje i šalje u App Store Connect, testeri dobiju pozivnicu i instaliraju preko TestFlight aplikacije. Za to u repo treba dodati sertifikat i API ključ kao GitHub Secrets (posle otvaranja naloga).

## Lokalni razvoj
- Android: otvori folder u Android Studiju, pokreni `composeApp`.
- iOS (Mac): `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`, pa `cd iosApp && xcodegen generate` i otvori `iosApp.xcodeproj` (postavi `KMP_FRAMEWORK_DIR` na `composeApp/build/bin/iosSimulatorArm64/debugFramework`).

## Napomene
- Prijava: Cognito `USER_PASSWORD_AUTH`, čuva se samo refresh token (lozinka se ne pamti).
- Izveštaj „Sa telefona“ se čuva lokalno (`istorija.json`), sa GPS koordinatama u CSV-u.
- Alarmi: na Androidu provera i u pozadini (na 15 min) sa notifikacijom; na iOS-u dok je aplikacija otvorena.
