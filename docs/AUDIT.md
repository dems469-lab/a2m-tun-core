# Audit initial du dépôt A2M TUN

Date de vérification : 2026-10-25  
Branche examinée : `dev` (`a548a79`, `Bump version 1.15.0-alpha.13`)

## Résumé

Le dépôt est une base de l'application SagerNet sing-box for Android (SFA), version `1.15.0-alpha.13`, et non une application A2M TUN déjà personnalisée. Le projet Android est principalement Kotlin et utilise Android Gradle Plugin, Kotlin, Compose et des variantes par fournisseur. L'architecture et l'entrée TUN native de sing-box sont celles fournies par cette base.

## Moteur `libbox`

`app/build.gradle.kts` déclare les dépendances locales suivantes : `app/libs/libbox.aar` pour `play` et `other`, et `app/libs/libbox-legacy.aar` pour `otherLegacy`. Aucun de ces fichiers AAR n'est suivi par Git, et aucun script ou workflow suivi ne les télécharge ou ne les compile. Leur provenance et leur version exacte ne peuvent donc pas être attestées depuis cet état du dépôt. Le build nécessite de fournir ces artefacts localement ou d'ajouter une étape de compilation/téléchargement documentée dans une tâche ultérieure. `version.properties` fournit `GO_VERSION=go1.26.10`, mais ne précise pas à lui seul le commit/version de sing-box correspondant.

## Versions et configuration constatées

| Élément | Valeur constatée |
| --- | --- |
| Gradle Wrapper | 9.7.0 (`gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin | 9.3.1 (`build.gradle.kts`) |
| Kotlin | 2.4.10 (`build.gradle.kts`) |
| `compileSdk` / révision mineure | 37 / 1 |
| `targetSdk` | 37 |
| `minSdk` | 24 (`other` et `play`), 21 (`otherLegacy`) |
| NDK demandé | 28.0.13004108 |
| Go indiqué dans le dépôt | go1.26.10 |
| Version Android | code 747, nom 1.15.0-alpha.13 |
| Variante cible de la feuille de route | `other` |

La configuration confirme bien la dimension de variantes `vendor` (`play`, `other`, `otherLegacy`). `settings.gradle.kts` nomme encore le projet `sing-box`. Le projet conserve le namespace et l'identifiant `io.nekohasekai.sfa`.

## Environnement vérifié dans cette session

Commandes exécutées : `./gradlew --version`, `java -version`, `go version`, vérification des variables Android SDK/NDK et recherche de fichiers AAR.

- `./gradlew --version` a terminé : Gradle 9.7.0 fonctionne sur OpenJDK 17.0.20.1, Linux aarch64.
- `go version` n'a pas pu être exécuté : Go n'est pas installé (`go: command not found`).
- `ANDROID_HOME`, `ANDROID_SDK_ROOT` et `ANDROID_NDK_HOME` ne sont pas définis.
- Aucun répertoire `app/libs` ni AAR suivi par Git n'a été trouvé.
- Aucun résultat de build Android n'est attesté dans cet audit.

`./gradlew tasks --all` a échoué avant l'affichage des tâches, lors de la résolution du plugin `com.android.application` version `9.3.1` (échec après recherche dans Google, Maven Central et Gradle Plugin Portal). Cela empêche de vérifier localement le graphe des tâches ou d'attribuer cet échec à l'absence des AAR. Le journal complet affiché par Gradle se termine par `BUILD FAILED in 1m 13s` ; aucune relance n'a été faite, car l'environnement manque également le JDK 21, le SDK Android et Go nécessaires aux étapes ultérieures.

Le JDK 21, le SDK Android 37 et le NDK attendu ne sont pas confirmés dans l'environnement courant. Il faut les vérifier dans l'environnement CI avant d'établir un build reproductible.

## Points à traiter pour l'identité A2M TUN

La personnalisation relève de la Phase 5 et n'a pas été effectuée dans cet audit. Les principaux points déjà repérés sont :

- identité Android : `namespace`, `applicationId`, packages Kotlin/Java, AIDL et actions de manifeste utilisent `io.nekohasekai.sfa` ;
- nom et ressources : `app_name` est `sing-box` dans plusieurs ressources localisées, et des textes de l'application nomment le produit ;
- nom de projet et artefacts : `rootProject.name` vaut `sing-box`, et les APK sont préfixés `SFA-` ;
- mises à jour et distributions : le flavor `other` active le code GitHub de mise à jour et les sources GitHub/F-Droid ; ces destinations doivent être examinées et adaptées avant toute distribution A2M ;
- documentation et liens : le README renvoie à la documentation SFA et décrit l'application d'origine.

Il faudra conserver les notices de copyright et la licence GPL-3.0, respecter la restriction de nom figurant dans le README d'origine, et ne pas utiliser la clé publique `app/release.keystore` pour signer A2M TUN. Cette clé est suivie par Git dans l'état audité ; sa suppression ou son remplacement sort du périmètre de cette PR et requiert la validation humaine prévue par la feuille de route.

## Risques et suites proposées

1. **Bloquant pour compiler :** les AAR `libbox` requis manquent du dépôt et leur mode de production n'est pas défini ici.
2. **Reproductibilité :** l'association entre `GO_VERSION` et une révision précise de sing-box n'est pas documentée par les fichiers examinés.
3. **Environnement :** Go, Android SDK et NDK ne sont pas disponibles/configurés dans cette session ; seul le Wrapper Gradle a été vérifié.
4. **Identité/distribution :** l'application garde le nom, le package et les canaux de mise à jour de l'amont ; traiter dans la PR de rebranding dédiée.
5. **Signature :** le keystore d'origine est versionné. Ne pas l'utiliser pour A2M TUN ; la gestion de sa suppression relève d'une décision séparée et humaine.

Prochaine tâche proposée : Phase 2, préparation de la structure du dépôt, puis Phase 4 pour déterminer et automatiser la fourniture des artefacts `libbox` et obtenir le premier build CI.
