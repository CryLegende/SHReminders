<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHReminders

*Rappels programmés par joueur (ex. prochain vote), même hors ligne, avec placeholder du temps restant.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Rappels programmés par joueur (ex. prochain vote), même hors ligne, avec placeholder du temps restant.

Utilisé sur : **Opale**.

Avec `SHNetwork`, les rappels sont communs à tous les serveurs Opale et chaque rappel n'est déclenché qu'une fois pour tout le réseau (voir « Mode réseau »). Sans lui, le plugin se comporte comme sur un serveur seul : tout est dans `reminders.yml`.

## Dépendances

- Optionnelle : `SHNetwork`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shreminders schedule <joueur> <id>` | — | Planifie un rappel pour un joueur, même hors ligne. L'ancienne forme `/shreminders <joueur> <id>` fonctionne toujours. | `shreminders.admin.schedule` |
| `/shreminders import` | — | Avec SHNetwork : reprend `reminders.yml` dans la base du réseau. | `shreminders.admin.import` |
| `/shreminders reload` | — | Recharge `config.yml` et `messages.yml`. Un fichier illisible annule le rechargement : l'ancienne configuration reste active et le fichier fautif est indiqué. Les rappels en attente dont l'identifiant a disparu de `config.yml` sont signalés. La section `reseau` n'est lue qu'au démarrage. | `shreminders.admin.reload` |
| `/shreminders list [joueur]` | — | Rappels en attente : le nombre par identifiant, ou ceux d'un joueur avec le temps restant. | `shreminders.admin.list` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shreminders.admin` | Toute l'administration (parent des quatre permissions suivantes). | `op` |
| `shreminders.admin.schedule` | Programmer un rappel pour un joueur. | `op` |
| `shreminders.admin.import` | Avec SHNetwork, reprendre `reminders.yml` dans la base du réseau. | `op` |
| `shreminders.admin.reload` | Recharger `config.yml` et `messages.yml`. | `op` |
| `shreminders.admin.list` | Consulter les rappels en attente. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHReminders/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

`messages.yml` contient les textes des commandes et ceux du mode réseau. La table du mode réseau est décrite dans <a href="sql/schema.sql">sql/schema.sql</a>.

## Mode réseau (SHNetwork)

Quand `SHNetwork` est installé et que `reseau.actif` vaut `true` (valeur par défaut), tous les serveurs partagent les mêmes rappels. Sans SHNetwork, ou avec `reseau.actif: false`, rien ne change.

- **Données.** Les rappels sont dans la table `reminders_active` (base `bo_net`), une ligne par joueur et par rappel. `reminders.yml` n'est plus ni lu ni écrit. Chaque serveur garde une copie en mémoire (placeholder et planificateur) : après un changement, le serveur publie `changed` sur le canal `reseau.canal` et les autres relisent cette ligne.
- **Programmation.** `/shreminders schedule <joueur> <id>` retrouve le joueur parmi les joueurs connectés au réseau, puis dans la table `reseau.table-comptes` (comptes connus du réseau), puis parmi ceux déjà venus sur ce serveur. L'UUID est celui du réseau (hors ligne, dérivé du pseudo avec sa casse), jamais celui d'une recherche Mojang. Un rappel déjà programmé pour ce joueur est remplacé, comme avant.
- **Une seule exécution.** Il n'y a pas de serveur meneur : chaque serveur parcourt sa copie chaque seconde et réclame en base les rappels échus. Le rappel principal se réclame par un `UPDATE` (ou un `DELETE` sans répétition) dont la condition reprend l'état lu : pas encore déclenché et à cette heure. Le serveur qui touche la ligne exécute la commande console du rappel ; l'autre voit zéro ligne et relit. C'est donc la base qui décide : un serveur qui tombe ne retarde rien et la même commande n'est jamais exécutée deux fois. Si le serveur qui a réclamé s'arrête avant d'exécuter la commande, elle est perdue plutôt que rejouée.
- **Affichage.** Le serveur qui a réclamé affiche le rappel à son joueur s'il est chez lui et publie `fire` ; les autres serveurs affichent le titre, le message, la barre d'action et le son si le joueur est chez eux. Le rappel principal est réclamé par n'importe quel serveur, même si le joueur est hors ligne (la commande console part quand même).
- **Répétitions.** Elles n'ont lieu que pour un joueur connecté, comme avant : seul le serveur où il se trouve les réclame. Le compteur et l'heure suivante sont dans la ligne, donc un joueur qui change de serveur ne voit ni répétition en double ni répétition perdue.
- **Placeholder.** `%shreminders_next_...%` lit la copie en mémoire, jamais la base.
- **Rappel absent de `config.yml`.** Un serveur dont la configuration ne connaît pas l'identifiant d'un rappel l'ignore (avertissement dans la console) et ne supprime pas sa ligne, qu'un autre serveur peut encore déclencher.
- **Base injoignable.** Au démarrage, le plugin réessaie toutes les 10 secondes ; en attendant, le placeholder répond `none` et rien n'est déclenché. Les rappels échus le sont dès que la base revient. `/shreminders` répond « La base des rappels ne répond pas ».
- **Mode réseau inutilisable.** Si SHNetwork est présent mais que `reseau.base-de-donnees` est incomplète, le plugin se désactive au démarrage au lieu de laisser chaque serveur exécuter les commandes de ses propres rappels.

`/shreminders import` reprend dans la base les rappels de `reminders.yml`. À lancer une fois, sur un serveur, avant les premiers rappels en mode réseau ; une ligne déjà en base n'est pas modifiée, donc la commande peut être relancée sans remettre un rappel à zéro. Les horloges des serveurs doivent être synchronisées (même machine ou NTP).

### Installation

1. Base : `mysql -u root < sql/schema.sql` (crée `reminders_active` et l'utilisateur `shreminders`, avec lecture seule sur la table des comptes). Remplacer `CHANGE_ME` par le mot de passe.
2. `plugins/SHReminders/config.yml`, section `reseau` : `url`, `utilisateur` et `mot-de-passe`. La même base et les mêmes rappels sur tous les serveurs.
3. Poser le jar sur chaque serveur, avec SHNetwork. Le jar charge HikariCP et le pilote MariaDB par `libraries:` (le serveur ne les embarque pas) ; sans SHNetwork, ils ne sont pas utilisés.
4. `/shreminders import` sur un serveur.

Au démarrage, la console indique `SHNetwork détecté : rappels en base partagée` puis le nombre de rappels chargés.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
