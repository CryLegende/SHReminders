package org.Cry_Legende.sHReminders;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRulesTest {

    @Test
    void derniereRepetitionDeVote() {
        // les rappels de vote ont maxRepeat: 2 : la répétition 1 puis la 2 (la dernière) suppriment la ligne
        assertFalse(NetworkReminders.isLastRepeat(0, 2));
        assertTrue(NetworkReminders.isLastRepeat(1, 2));
    }

    @Test
    void repetitionsSansFin() {
        // -1 (sans fin) et 0 (rappel sans répétition) ne terminent jamais le compteur
        assertFalse(NetworkReminders.isLastRepeat(500, -1));
        assertFalse(NetworkReminders.isLastRepeat(500, 0));
    }

    @Test
    void uuidDuReseauEstCeluiDuPseudoAvecSaCasse() {
        // paire lue dans reminders.yml de la prod : l'UUID hors ligne dépend de la casse exacte du pseudo
        assertEquals(UUID.fromString("6c0f9a22-392f-3b0d-a810-10c970feb01f"), NetworkReminders.offlineUuid("One_Namikaze"));
    }
}
