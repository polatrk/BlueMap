# BlueMap pour Bedrock Dedicated Server

Implémentation de BlueMap pour **Bedrock Dedicated Server (BDS) 1.26.5x**. Elle fait tourner le plugin
BlueMap complet (webserver, joueurs en direct, mises à jour en direct, marqueurs, API, commandes)
à côté de BDS, sans serveur Java ni Geyser.

## Fonctionnement

- **Lecture du monde** (`core/.../world/bedrock`) : lecteur LevelDB en Java pur, **en lecture seule**,
  directement dans le dossier du monde de BDS. Pas de copie, pas d'écriture, le `LOCK` de BDS est
  ignoré. Les tables ouvertes restent lisibles même si BDS les supprime pendant une compaction.
- **Blocs et biomes** : les états de blocs Bedrock sont traduits en états Java grâce à une table
  générée à partir des mappings de [GeyserMC](https://github.com/GeyserMC/mappings) (MIT) et du rapport
  de données du serveur Java 26.3 (`tools/generate-mappings.mjs`). BlueMap rend donc le monde avec les
  modèles et textures Java 26.3 (téléchargés depuis Mojang au premier lancement, après accord dans
  `core.conf`).
- **Lumière** : Bedrock ne sauvegarde pas l'éclairage. Il est calculé de façon approchée (ciel par
  colonne, propagation des blocs lumineux dans le chunk), ce qui suffit pour l'ombrage et le mode nuit.
- **Changements** : chaque chunk reçoit comme « horodatage » le plus grand numéro de séquence LevelDB
  de ses enregistrements. Ce numéro change seulement si le chunk est réécrit, et les compactions le
  conservent. Le dossier `db/` est surveillé pour re-rendre uniquement les régions modifiées.
- **Joueurs** : un behavior pack (`addon/`) envoie positions, rotation, mode de jeu, accroupi,
  invisibilité et lumière, soit par la console de BDS (variante `console`, API stables uniquement),
  soit en HTTP (variante `net`, Beta APIs). Il ajoute aussi la commande `/bluemap` en jeu pour les
  opérateurs ; la réponse revient par `tellraw`.
- **Skins** : récupérés via l'API publique de GeyserMC à partir du XUID, relevé dans la console de BDS.

## Compilation

```bash
# Java 25 (JDK) requis
./gradlew -PonlyImplementations=cli,bedrock :bedrock:shadowJar
# -> implementations/bedrock/build/libs/bedrock-<version>-shadow.jar

# add-on (Node.js) : régénère les .mcpack embarqués dans le jar
cd implementations/bedrock/addon && npm install && npm run build
```

## Docker (BlueMap sur sa propre machine / LXC)

Le [`docker-compose.yml`](../../docker-compose.yml) à la racine du dépôt suffit à lui seul (l'image est construite depuis GitHub).
Il attend à côté de lui la clé ssh privée vers le serveur BDS, nommée `ssh_key` :

```bash
ssh-keygen -t ed25519 -N "" -C bluemap -f ssh_key
```
 Mise en place complète, avec le pont ssh vers la console de BDS : [`docker/README.md`](docker/README.md).

## Installation automatique (BDS dans Docker)

Sur la machine qui fait tourner le conteneur BDS (BDS lancé dans `screen -S mc-server`) :

```bash
curl -fsSLO https://raw.githubusercontent.com/polatrk/BlueMap/bedrock/implementations/bedrock/deploy/install.sh
chmod +x install.sh
sudo ./install.sh                      # RENDER_CAVES=yes pour rendre le sous-sol
```

Le script installe Java 25, l'add-on (les packs existants du monde sont conservés et les fichiers
modifiés sauvegardés en `*.bluemap-backup`), BlueMap dans `/opt/bluemap` et un service systemd
`bluemap` à priorité basse. Il demande confirmation avant de redémarrer BDS.

## Installation manuelle

1. Exporter l'add-on : `java -jar bluemap-bedrock.jar --export-addon packs/`.
2. Décompresser `bluemap-console.mcpack` dans `<bds>/development_behavior_packs/bluemap`, puis ajouter
   `{ "pack_id": "6f1d3a52-9c4e-4b8a-bb0e-1d2a7c5e9f01", "version": [1, 0, 0] }` au fichier
   `worlds/<monde>/world_behavior_packs.json` (faire une sauvegarde avant).
3. Dans `server.properties` : `content-log-console-output-enabled=true`, sinon BDS n'affiche pas la
   sortie des scripts.
4. Premier lancement : `java -jar bluemap-bedrock.jar` crée `bluemap/bedrock.conf` et les configs
   BlueMap. Choisir `console-mode` dans `bedrock.conf`, mettre `accept-download: true` dans
   `bluemap/core.conf`, puis relancer.

Modes de console (`bedrock.conf`) :
- `wrapper` : BlueMap lance BDS et relaie sa console ; le terminal devient la console du serveur.
- `external` : BDS tourne ailleurs (Docker, screen…). La sortie est lue avec `console-command`, par
  exemple `docker logs -f --tail 0 <conteneur>`, et les commandes passent par `command-template`, par
  exemple `docker exec <conteneur> screen -S mc-server -p 0 -X stuff "{cmd}\r"`.
  Dans le terminal, `bds <commande>` envoie une commande au serveur, et `stop` ne quitte que BlueMap.
- `none` : pas de console. Le monde n'est mis à jour que lorsque BDS écrit sur disque, et la position
  des joueurs nécessite la variante `net`.

Avec une console, BlueMap fait régulièrement `save hold` / `save query` / `save resume` pour que BDS
écrive les chunks modifiés (`save-interval`).

Dans le terminal, `bluemap <commande>` exécute les commandes BlueMap (`bluemap help`).

## Web

- Menu ☰ → **Couches (Y)** : n'affiche que les blocs entre deux hauteurs. Pour voir le sous-sol,
  régler `remove-caves-below-y: -10000` dans la config de la carte et refaire le rendu.
- Les distances de rendu se règlent dans Paramètres. Les valeurs par défaut et maximales sont dans
  `webapp.conf`.

## Limites connues

- Panneaux, bannières, têtes posées (block entities) et entités ne sont pas encore lus.
- BDS n'écrit pas tout le terrain généré sur disque (il écrit notamment les chunks modifiés, avec du
  retard) : certaines zones explorées peuvent rester vides sur la carte.
- Les mappings doivent correspondre à la version de BDS. Pour une autre version, régénérer la table
  avec `tools/generate-mappings.mjs`.
