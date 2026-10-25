# BlueMap pour Bedrock dans Docker (machine / LXC séparée)

Deux LXC Proxmox :
- **LXC BDS** : le serveur Bedrock dans Docker (conteneur `mc-bedrock-server`, BDS dans `screen -S mc-server`) ;
- **LXC BlueMap** : le `docker-compose.yml` à la racine du dépôt (il suffit à lui seul : l'image est construite depuis GitHub).

BlueMap a besoin de deux choses côté BDS :
1. **les fichiers du serveur, en lecture seule** : un dossier de l'hôte Proxmox monté dans les deux LXC ;
2. **la console de BDS**, par ssh : positions des joueurs (add-on), sauvegardes forcées pour que la carte
   suive le jeu, réponses à `/bluemap` en jeu. La clé ssh est limitée à `bluemap-bridge.sh`, qui ne
   sait que lire la console ou envoyer une ligne de commande.

## 1. Partager les fichiers du serveur (hôte Proxmox)

Les données de BDS doivent être dans un dossier de l'hôte et non plus dans un volume Docker nommé,
par exemple `/srv/bds-data`.

```bash
# sur l'hôte Proxmox (arrêter BDS avant)
mkdir -p /srv/bds-data
pct set <id LXC BDS>     -mp0 /srv/bds-data,mp=/srv/bds-data
pct set <id LXC BlueMap> -mp0 /srv/bds-data,mp=/mnt/bds-data,ro=1
```

Dans le LXC BDS, copier une fois les données actuelles :
```bash
docker run --rm -v mc-bedrock-server_minecraft_data:/from -v /srv/bds-data:/to alpine cp -a /from/. /to/
```
puis, dans le `docker-compose.yml` de BDS, remplacer le volume nommé par `- /srv/bds-data:/server-data`.

LXC non privilégiés : BDS écrit ses fichiers en 644 / 755, ils restent donc lisibles depuis le LXC
BlueMap malgré la correspondance des identifiants.

## 2. Add-on et server.properties (BDS)

```bash
docker run --rm -v /tmp/packs:/out --entrypoint java bluemap-bedrock:local -jar /app/bluemap-bedrock.jar --export-addon /out
```
- Décompresser `bluemap-console.mcpack` dans `/srv/bds-data/development_behavior_packs/bluemap`.
- Ajouter `{ "pack_id": "6f1d3a52-9c4e-4b8a-bb0e-1d2a7c5e9f01", "version": [1, 0, 0] }` à
  `worlds/<monde>/world_behavior_packs.json` en gardant les packs existants (sauvegarder le fichier avant).
- Mettre `content-log-console-output-enabled=true` dans `server.properties`, puis redémarrer BDS.

## 3. Pont ssh (LXC BDS)

```bash
sudo install -m 755 bluemap-bridge.sh /usr/local/bin/bluemap-bridge.sh
sudo useradd -m -G docker -s /bin/sh bluemap      # utilisateur autorisé à utiliser docker
# si le conteneur ou la session screen ont d'autres noms :
# echo 'BDS_CONTAINER=mc-bedrock-server' | sudo tee /etc/default/bluemap-bridge
```
Le serveur ssh doit tourner dans le LXC BDS (`apt install openssh-server`).

## 4. Lancer BlueMap (LXC BlueMap)

```bash
mkdir bluemap && cd bluemap
curl -fsSLO https://raw.githubusercontent.com/polatrk/BlueMap/bedrock/docker-compose.yml
# adapter BDS_SSH (utilisateur@IP du LXC BDS) et le chemin du montage si besoin
docker compose up -d --build        # premier build : plusieurs minutes (BlueMap est compilé)
docker compose logs bluemap         # affiche la ligne à autoriser
```
1. Copier la ligne `command="/usr/local/bin/bluemap-bridge.sh",...` affichée dans
   `/home/bluemap/.ssh/authorized_keys` du LXC BDS (fichier en 600, dossier `.ssh` en 700).
2. `docker compose restart`. La carte est sur le port 8100.

Les ressources du client Minecraft (textures, modèles) sont téléchargées automatiquement depuis Mojang au
premier démarrage (`ACCEPT_DOWNLOAD: "true"`, ce qui revient à accepter l'EULA de Minecraft pour ce
téléchargement ; mettre `"false"` pour refuser).

Pour vérifier le pont depuis le LXC BlueMap (doit afficher la console de BDS) :
```bash
docker exec bluemap ssh -i /data/ssh/id_ed25519 -o UserKnownHostsFile=/data/ssh/known_hosts bluemap@<IP BDS> logs
```

## Mettre à jour

`docker compose build --no-cache && docker compose up -d` recompile depuis la dernière version de la
branche `bedrock`.
