# Le Flâneur de Paris

MVP Android d’exploration urbaine : la carte de Paris est recouverte d’un brouillard découpé en cellules de 100 m. Une position GPS valide au premier plan découvre une cellule ; les monuments historiques situés dans cette cellule deviennent visibles et la progression est conservée localement.

## Stack

Kotlin, Gradle Kotlin DSL, Jetpack Compose Material 3, MVVM, Room, Hilt, Coroutines/Flow, MapLibre Native Android, Fused Location Provider et kotlinx.serialization. Le projet utilise un seul module `app` et cible API 26+.

## Compiler et lancer

1. Ouvrir le dossier dans Android Studio.
2. Synchroniser Gradle et lancer la configuration `app` sur un appareil ou un émulateur avec Google Play Services.
3. Accorder la localisation depuis l’écran Carte. Le suivi en arrière-plan est optionnel et peut demander « Autoriser tout le temps » dans les réglages Android de l’application.

Pour un APK debug :

```bash
./gradlew assembleDebug
```

Pour un Android App Bundle release :

```bash
./gradlew bundleRelease
```

## Carte et données

L’URL du style est centralisée dans `MAP_STYLE_URL` dans `app/build.gradle.kts`. La valeur par défaut utilise le style de démonstration MapLibre ; avant publication, configurer un fournisseur de tuiles adapté et ses conditions d’utilisation. Aucune clé réelle n’est stockée dans le dépôt.

Depuis Réglages, le bouton « Télécharger la carte de Paris » crée une région MapLibre hors connexion couvrant la bounding box du jeu aux niveaux de zoom 10 à 16. Le téléchargement doit être fait avec une connexion, idéalement en Wi-Fi ; une fois terminé, MapLibre réutilise localement le style, les polices, les icônes et les tuiles. La progression du téléchargement est affichée dans Réglages et la carte peut être supprimée séparément de la progression d’exploration.

Le téléchargement hors connexion dépend des droits et conditions du fournisseur du style et des tuiles. Le serveur public `tile.openstreetmap.org` ne doit pas être utilisé comme solution de production illimitée. Pour une publication Play Store, choisir un fournisseur adapté, conserver les attributions et vérifier les conditions ODbL et celles du style utilisé.

`app/src/main/assets/paris_monuments.json` contient 1 970 entrées dédupliquées à partir du CSV fourni, filtrées par la bounding box du MVP. Ce n’est pas une promesse d’exhaustivité : voir [DATA_IMPORT.md](DATA_IMPORT.md) pour régénérer l’asset depuis un export actualisé.

La grille est calculée en Web Mercator EPSG:3857 avec des cellules stables de 100 m. Pour garder la carte fluide, le brouillard est généré pour la fenêtre de Paris affichée avec une marge ; la base ne stocke que les cellules découvertes.

En debug, un appui long sur la carte simule la découverte de la cellule. Cette fonction est protégée par `BuildConfig.DEBUG` et n’est pas disponible dans le build release.

## Tests

```bash
./gradlew test
./gradlew connectedAndroidTest
./gradlew lint
```

Les tests couvrent les conversions géographiques, les limites de cellules, les filtres de découverte, la visibilité, les statistiques et l’insertion Room sans doublon.

## Limites et publication

Le MVP ne possède ni compte ni serveur, ne conserve pas l’historique GPS brut et ne détecte pas encore la fausse position, la vitesse, la durée de présence ou le polygone administratif exact de Paris. Avant publication : choisir un hébergeur de tuiles pérenne, finaliser la politique de confidentialité Play Console, vérifier les licences du style et des données, signer l’AAB, effectuer des tests sur appareils réels et compléter l’optimisation de regroupement des marqueurs.

L’attribution `© OpenStreetMap contributors` reste visible dans l’application. Les données OSM sont soumises à l’ODbL ; les données patrimoniales proviennent du Ministère de la Culture selon les conditions applicables à la source fournie.
