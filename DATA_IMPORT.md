# Import du catalogue patrimonial

Le fichier embarqué `app/src/main/assets/paris_monuments.json` est généré depuis le CSV fusionné Ministère de la Culture / OpenStreetMap fourni avec la demande. Le CSV possède 1 348 colonnes et un en-tête `ref:mhs` dupliqué ; le script d’import utilise un parseur CSV standard et ne dépend pas de l’unicité des noms de colonnes.

Pour remplacer les données :

1. Exporter un CSV UTF-8 avec au minimum `lat`, `lon`, `name` ou `name:fr`, un identifiant Mérimée (`ref:mhs` ou `ref:FR:Merimee`) et, si disponible, `osm_type`/`osm_id`.
2. Filtrer les coordonnées sur `48.815..48.903` et `2.224..2.470`.
3. Produire des identifiants stables : `merimee-PA...` est préféré, sinon `osm-node/...`, `osm-way/...` ou `osm-relation/...`.
4. Dédupliquer par identifiant et remplacer l’asset JSON par un tableau d’objets avec les champs `id`, `name`, `latitude`, `longitude`, `category`, `description`, `imageUrl`, `imagePageUrl`, `imageAuthor`, `imageLicense`, `imageLicenseUrl`, `imageSource`, `imageLookupCompleted`, `merimeeReference`, `osmId`.
5. Désinstaller l’application ou vider sa base locale après changement si un catalogue a déjà été importé : l’import Room est volontairement exécuté une seule fois.

## Images réutilisables

Le catalogue peut être enrichi avec des images Wikimedia Commons documentées :

```bash
python tools/enrich_wikimedia_images.py
```

Le script utilise d’abord l’identifiant Mérimée dans Wikidata, puis une
recherche Commons groupée et ralentie pour respecter l’API. Il ne conserve que
les fichiers indiqués comme domaine public, CC0, CC BY ou CC BY-SA, et
enregistre l’auteur, la licence et la page source. Les anciennes URL `imageUrl`
sans métadonnées de licence sont ignorées par l’import. Pour tenter en plus des
recherches géographiques, plus lentes et limitées, utiliser
`python tools/enrich_wikimedia_images.py --geosearch`.
Si l’enrichissement n’a pas été exécuté, l’application effectue une recherche
Commons équivalente à la demande lorsqu’une vignette devient visible.

Le catalogue fourni dans ce dépôt contient 1 970 monuments parisiens dédupliqués. Les cellules de chaque monument sont recalculées à l’import avec le même algorithme Web Mercator que la localisation.
