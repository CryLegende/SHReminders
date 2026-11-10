package org.Cry_Legende.sHReminders.network;

import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ce dont le mode réseau a besoin de SHNetwork. Seule {@link ShNetworkBus} connaît ses classes : sans lui, aucune
 * classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Joueur connecté au réseau : son UUID (hors ligne, le même partout) et son pseudo avec sa casse. */
    record Found(UUID uuid, String name) {
    }

    /** Publie à destination des autres serveurs (pas de celui-ci). Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /**
     * Écoute un canal.
     *
     * @param mainThread vrai pour recevoir sur le fil principal
     */
    void listen(String channel, Consumer<JsonObject> handler, boolean mainThread);

    /** Joueur connecté sur ce serveur ou sur un autre, retrouvé par son pseudo. */
    Optional<Found> find(String name);

    /** Pseudo d'un joueur connecté sur ce serveur ou sur un autre, retrouvé par son UUID. */
    Optional<String> onlineName(UUID uuid);
}
