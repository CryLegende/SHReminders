-- Table de SHReminders avec SHNetwork. Le plugin la crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à la créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

-- Un rappel par joueur et par identifiant de config.yml. Le déclenchement est réclamé par une instruction UPDATE ou
-- DELETE dont la condition porte sur l'état lu (rappel principal pas encore déclenché à telle heure, ou répétition à tel
-- numéro et telle heure) : un seul serveur touche la ligne, c'est lui qui exécute la commande du rappel.
CREATE TABLE IF NOT EXISTS bo_net.reminders_active (
    uuid              CHAR(36)     NOT NULL,                  -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    reminder_id       VARCHAR(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, -- identifiant du rappel dans config.yml
    player_name       VARCHAR(64)  NULL,                      -- pseudo du joueur à la programmation
    main_trigger_time BIGINT       NOT NULL,                  -- heure du rappel principal, en millisecondes depuis 1970
    main_triggered    TINYINT(1)   NOT NULL DEFAULT 0,        -- 1 une fois le rappel principal déclenché
    repeat_enabled    TINYINT(1)   NOT NULL DEFAULT 0,        -- 1 si le rappel a des répétitions
    repeat_delay      BIGINT       NOT NULL DEFAULT 0,        -- délai entre deux répétitions, en millisecondes
    max_repeat        INT          NOT NULL DEFAULT 0,        -- nombre de répétitions (-1 : sans fin)
    repeat_count      INT          NOT NULL DEFAULT 0,        -- répétitions déjà faites
    next_repeat_time  BIGINT       NOT NULL DEFAULT 0,        -- heure de la prochaine répétition, en millisecondes depuis 1970
    PRIMARY KEY (uuid, reminder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shreminders'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.reminders_active TO 'shreminders'@'127.0.0.1';
-- Table des comptes connus du réseau (reseau.table-comptes), en lecture seule
GRANT SELECT ON Global.sh_track_firstseen TO 'shreminders'@'127.0.0.1';
