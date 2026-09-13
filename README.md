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

La carte démarre avec un style vide local puis ajoute le fond raster OSM, sans attendre le style de démonstration distant. `MAP_STYLE_URL` dans `app/build.gradle.kts` reste utilisé par le module de téléchargement hors connexion. Avant publication, configurer un fournisseur de tuiles adapté et ses conditions d’utilisation. Aucune clé réelle n’est stockée dans le dépôt.

Depuis Réglages, le bouton « Télécharger la carte de Paris » crée une région MapLibre hors connexion couvrant la bounding box du jeu aux niveaux de zoom 10 à 16. Le téléchargement doit être fait avec une connexion, idéalement en Wi-Fi ; une fois terminé, MapLibre réutilise localement le style, les polices, les icônes et les tuiles. La progression du téléchargement est affichée dans Réglages et la carte peut être supprimée séparément de la progression d’exploration.

Le téléchargement hors connexion dépend des droits et conditions du fournisseur du style et des tuiles. Le serveur public `tile.openstreetmap.org` ne doit pas être utilisé comme solution de production illimitée. Pour une publication Play Store, choisir un fournisseur adapté, conserver les attributions et vérifier les conditions ODbL et celles du style utilisé.

`app/src/main/assets/paris_monuments.json` contient 1 970 entrées dédupliquées à partir du CSV fourni, filtrées par la bounding box du MVP. Ce n’est pas une promesse d’exhaustivité : voir [DATA_IMPORT.md](DATA_IMPORT.md) pour régénérer l’asset depuis un export actualisé.

Les images de monuments sont recherchées de façon conservatrice sur Wikimedia Commons. L’application conserve uniquement les images avec une licence réutilisable identifiée (domaine public, CC0, CC BY ou CC BY-SA), affiche l’auteur et la licence dans le détail du monument, puis met les vignettes en cache local dans `monument-image-cache`. Pour enrichir le fichier embarqué avant une publication, exécuter `python tools/enrich_wikimedia_images.py` ; le script regroupe et ralentit ses requêtes. Une recherche géographique complémentaire, plus lente, est disponible avec `--geosearch`. À défaut, la recherche se fait progressivement à l’affichage ; une connexion est alors nécessaire lors de la première consultation.

La grille est calculée en Web Mercator EPSG:3857 avec des cellules stables de 100 m. Pour garder la carte fluide, le brouillard est généré pour la fenêtre de Paris affichée avec une marge ; la base ne stocke que les cellules découvertes.

En debug, un appui long sur la carte simule la découverte de la cellule. Cette fonction est protégée par `BuildConfig.DEBUG` et n’est pas disponible dans le build release.

## Tests

### Performances au démarrage

Le catalogue est importé uniquement si son empreinte SHA-256 change (calculée à la compilation), ou si la base est vide. La lecture et le décodage s’effectuent hors du thread principal ; les métadonnées sont mises à jour dans une seule transaction, sans toucher aux découvertes ni aux images enregistrées. Augmenter le préfixe de version de l’import dans le repository si les règles de conversion changent.

Les cellules découvertes sont chargées indépendamment du catalogue et des statistiques. Le brouillard est construit et sérialisé en arrière-plan. Les marqueurs, indices et position ne sont actualisés que si leurs données changent. Chaque écran possède son propre renderer, suspend le rendu en arrière-plan et libère la MapView lorsqu’il disparaît.

Sur téléphone : comparer plusieurs lancements après arrêt complet, déplacer la carte avec beaucoup de secteurs découverts, alterner Carte/Collection puis passer en arrière-plan et revenir. Vérifier aussi une première installation, une mise à jour du catalogue et la remise à zéro de la progression. Les tuiles OSM absentes du cache dépendent toujours du réseau.

### Commandes

```bash
./gradlew test
./gradlew connectedAndroidTest
./gradlew lint
```

Les tests couvrent les conversions géographiques, les limites de cellules, les filtres de découverte, la visibilité, les statistiques et l’insertion Room sans doublon.

## Limites et publication

Le MVP ne possède ni compte ni serveur, ne conserve pas l’historique GPS brut et ne détecte pas encore la fausse position, la vitesse, la durée de présence ou le polygone administratif exact de Paris. Avant publication : choisir un hébergeur de tuiles pérenne, finaliser la politique de confidentialité Play Console, vérifier les licences du style et des données, signer l’AAB, effectuer des tests sur appareils réels et compléter l’optimisation de regroupement des marqueurs.

L’attribution `© OpenStreetMap contributors` reste visible dans l’application. Les données OSM sont soumises à l’ODbL ; les données patrimoniales proviennent du Ministère de la Culture selon les conditions applicables à la source fournie.
