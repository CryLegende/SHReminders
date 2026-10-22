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

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shreminders` | — | Planifie un rappel de vote pour un joueur. | `shreminders.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shreminders.admin` | Permission pour utiliser la commande /shreminders. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHReminders/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

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
