# SoundTouch

Appli Android pour piloter des enceintes Bose SoundTouch 20 en local, sans le cloud Bose.

## Fonctions

- Découverte automatique des enceintes (mDNS `_soundtouch._tcp`, balayage du réseau en secours)
- Marche/veille, lecture/pause, piste précédente/suivante, volume, coupure du son
- Ce qui joue, avec la pochette, mis à jour en temps réel (WebSocket)
- Multiroom : grouper/dégrouper, volume du groupe, regroupement automatique dès qu'une enceinte s'allume
- Widget d'écran d'accueil : ce qui joue + commandes ; « marche » allume et groupe tout

## API des enceintes

| Port | Usage |
|------|-------|
| 8090 | HTTP + XML : `/info`, `/now_playing`, `/volume`, `/key`, `/getZone`, `/setZone`, `/removeZoneSlave` |
| 8080 | WebSocket, sous-protocole `gabbo` : notifications `<updates>` |

## Compiler

Prérequis : JDK 17 et le SDK Android (platform 35). Indiquer le chemin du SDK dans `local.properties` :

```
sdk.dir=C\:/Users/<vous>/AppData/Local/Android/Sdk
```

Puis :

```
./gradlew assembleDebug
```

L'APK est dans `app/build/outputs/apk/debug/app-debug.apk`. Le téléphone doit être sur le même Wi-Fi que les enceintes.
