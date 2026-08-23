# Enrichissement patrimonial

Le script [`tools/enrich_monuments.py`](tools/enrich_monuments.py) traite le
CSV Mérimée/OSM en conservant les lignes, les valeurs et l’ordre des colonnes
source, y compris les en-têtes dupliqués. Il ajoute les scores
`importance_patrimoniale`, `notoriete`, `note_globale`, `percentile_interet` et
`etoiles` dans le CSV final.

## Exécution

```powershell
python tools/enrich_monuments.py `
  --input "C:\chemin\vers\source.csv" `
  --output-dir outputs\heritage_enrichment
```

Le script affiche d’abord un échantillon représentatif. Pour les recherches
Wikidata et les consultations Wikipédia, ajouter `--online`. Les QID Wikidata
sont préchargés par lots; les réponses sont conservées dans
`heritage_cache.sqlite3`, et une reprise n’effectue pas à nouveau les appels
déjà mis en cache.

Pour l’application parisienne, la passe recommandée est :

```powershell
python tools/enrich_monuments.py `
  --input "C:\chemin\vers\source.csv" `
  --output-dir outputs\heritage_enrichment `
  --online --pageview-scope paris --star-scope paris --request-interval 0.4
```

Cette option enrichit les entités Wikidata de tout le fichier, mais limite les
pageviews et les recherches de rattrapage aux coordonnées de Paris. Sans
`--pageview-scope paris`, les pageviews sont tentés pour tous les titres; l’API
Wikimedia utilise une requête par article et cette passe peut être beaucoup
plus longue.

Par défaut, les descriptions historiques `popHistoricalDescription` de
`app/src/main/assets/paris_monuments.json` sont aussi utilisées quand une
référence Mérimée correspond. Les propriétés Wikidata disponibles sur
l’architecte, le style, l’artiste, les événements historiques et les éléments
constitutifs ajoutent un signal documentaire plafonné; elles ne remplacent
pas le statut de protection.

## Descriptions complémentaires Wikipédia

Pour les monuments sans description POP et sans description locale, le script
[`tools/enrich_missing_descriptions.py`](tools/enrich_missing_descriptions.py)
cherche un court extrait d’introduction sur Wikipédia en français. Il utilise
d’abord les liens Wikipédia déjà présents, puis une recherche par nom et Paris.
Une correspondance n’est conservée que si le titre est suffisamment proche et,
quand Wikipédia fournit des coordonnées, si la distance est cohérente. Les
résultats incertains restent vides.

```powershell
python tools/enrich_missing_descriptions.py
```

Les réponses positives et négatives sont conservées dans
`outputs/description_enrichment/wikipedia_description_cache.sqlite3`. Le
script est donc relançable sans refaire les appels déjà traités. Les extraits
retenus sont écrits dans `description`, leur source dans `descriptionSource`
et leur page dans `wikipediaUrl`; le bouton Wikipédia de la fiche permet de
consulter la source.
Un rapport est écrit dans
`outputs/description_enrichment/description_enrichment_report.json`.

Pour la seconde passe, [`tools/enrich_monument_metadata.py`](tools/enrich_monument_metadata.py)
complète les cas restants avec les champs structurés POP, les correspondances
Wikidata exactes par référence Mérimée et les liens Wikipédia bruts du CSV.
Il conserve les `historicalDates`, les `protectionDates` et déduit
`historicalPeriod` parmi Préhistoire, Antiquité, Moyen Âge, Renaissance,
Époque moderne et Époque contemporaine.

```powershell
python tools/enrich_monument_metadata.py `
  --source-csv "C:\chemin\vers\export.csv"
```

Les notices POP et les correspondances Wikidata sont ajoutées au même cache
SQLite afin de rendre cette passe reprenable.
Le rapport de cette passe est distinct :
`outputs/description_enrichment/metadata_enrichment_report.json`.

La fréquentation physique est facultative et doit venir d’un export public
structuré fourni avec `--attendance-csv`. Le fichier doit contenir une colonne
de visiteurs (`visitors`, `visiteurs`, `visites`, `frequentation` ou
`attendance`) et peut contenir une référence Mérimée (`ref:mhs`, `merimee` ou
`reference`), un `year`/`annee`, un `source`, ainsi que `name`, `lat` et `lon`.
La référence Mérimée est prioritaire; le rapprochement nom + coordonnées est
accepté seulement à moins de 150 m et avec une similarité élevée.

## Règles documentées

`heritage=2` est interprété comme « classé MH » et `heritage=3` comme « inscrit
MH ». Les formulations textuelles `classé`, `inscrit`, `classement` et
`inscription` sont aussi reconnues. Une protection partielle détectée dans les
critères (façade, toiture, escalier, etc.) reçoit le sous-score partiel.

L’ancienneté utilise la première année trouvée dans les champs de protection,
et non une date de construction. Les points UNESCO nécessitent une référence
`ref:whc`, un opérateur `whc`/`unesco`, ou une mention WHC explicite dans un
champ de protection; la simple proximité géographique n’ajoute aucun point.

La fréquentation est interpolée sur une échelle logarithmique entre les repères
10 000 → 20, 50 000 → 50, 100 000 → 60, 200 000 → 70, 500 000 → 80,
1 000 000 → 90, 2 000 000 → 95 et 5 000 000 → 100. Les consultations des
12 derniers mois complets de l’API officielle Wikimedia sont transformées par
`log1p` avec un plafond de référence de 5 millions. Les sitelinks Wikidata
utilisent de même `log1p` avec 60 sitelinks comme repère haut.

Quand plusieurs indicateurs de notoriété sont présents, les poids sont 40 %
fréquentation physique, 40 % pageviews et 20 % sitelinks, renormalisés sur les
indicateurs disponibles. Une donnée absente n’est jamais transformée en zéro.
Sans indicateur, le script utilise un plancher explicite de 2; une page
Wikipédia fiable sans métrique vaut 5 et un QID fiable sans métrique vaut 3.

Enfin:

```text
note_globale = round(0.60 * importance_patrimoniale + 0.40 * notoriete)
```

Le `percentile_interet` est calculé sur la population de référence choisie par
`--star-scope` (Paris par défaut). Les seuils sont volontairement asymétriques
pour garder une majorité de monuments à 1 étoile : le 75e percentile ouvre la
catégorie 2 étoiles et le 95e percentile la catégorie 3 étoiles. Les seuils
réels en points sont enregistrés dans le résumé JSON, car les ex æquo peuvent
faire varier légèrement les effectifs.

## Fichiers produits

Dans le dossier de sortie:

* `monuments_enrichis.csv` : CSV source + les cinq scores;
* `monuments_enrichis_debug.csv` : détails du matching, des sous-scores et des sources;
* `monuments_a_verifier.csv` : rapprochements ambigus, coordonnées contradictoires ou fréquentations incertaines;
* `monuments_enrichis_summary.json` : compteurs, intégrité et distributions;
* `heritage_cache.sqlite3` : cache persistant des appels externes.
