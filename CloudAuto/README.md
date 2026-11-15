# Cloud Auto

App Android (Kotlin + Media3) che mostra su Android Auto i file audio di **Yandex Disk**:
navigazione per cartelle e riproduzione in streaming. Solo audio.
Accesso via **WebDAV** con login + password per app: nessuna app da registrare su Yandex.

> Stato: scheletro non ancora compilato né provato su un'auto. Aspettati qualche correzione al primo build.

## 1. Ottenere l'APK (senza Android Studio)

1. Crea un repository GitHub gratuito e carica il contenuto di questa cartella (compresa `.github`).
2. Vai su **Actions → Build APK → Run workflow**.
3. Al termine scarica `app-debug.apk` dagli *Artifacts* e installalo sul telefono
   (consenti l'installazione da fonti sconosciute quando richiesto).

In alternativa apri la cartella in Android Studio e premi Run.

## 2. Password per app di Yandex

Su <https://id.yandex.com> → **Sicurezza → Password per le app** crea una password per
**Files (WebDAV)**. Se l'opzione non compare, attiva prima la verifica in due passaggi.
Nell'app inserisci il tuo login Yandex e quella password, poi «Salva e verifica».

## 3. Provare con Android Auto

1. Sul telefono: Android Auto → Impostazioni → tocca 10 volte «Versione» → attiva la modalità sviluppatore.
2. Menu ⋮ → **Impostazioni sviluppatore** → attiva **Origini sconosciute** (serve per le app non installate da Play Store).
3. Apri «Cloud Auto» sul telefono e accedi.
4. Collega l'auto (o usa il **Desktop Head Unit**) e scegli «Cloud Auto» tra le app multimediali.

## Struttura

| File | Ruolo |
|---|---|
| `MainActivity.kt` | Login sul telefono con verifica della connessione |
| `YandexDiskApi.kt` | WebDAV: elenco cartelle/file audio (PROPFIND) e sorgente di lettura |
| `MusicLibraryService.kt` | `MediaLibraryService`: albero sfogliabile + player ExoPlayer |
| `CredentialStore.kt` | Login e password per app in SharedPreferences private |
| `res/xml/automotive_app_desc.xml` | Dichiara l'app come «media» per Android Auto |

Alla lettura di un brano l'app chiede a Yandex dove scaricarlo: se risponde con un redirect
usa il link temporaneo, altrimenti legge dal WebDAV con le credenziali (cache di 5 minuti).
Quando si tocca un brano, l'intera cartella viene messa in coda.

## Prossimi passi

- Provider **Mega** dietro un'interfaccia comune (`CloudProvider`) con il Mega SDK.
- Metadati (titolo/artista/copertina) letti dai tag ID3.
- Cache offline, ricerca, ultimi ascolti.
- Icona dell'app e icona di notifica dedicate.
