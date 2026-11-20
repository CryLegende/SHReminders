package org.Cry_Legende.sHReminders;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutedGroupsTest {

    @Test
    void uuidHorsLigneCommeLeSite() {
        // le site écrit la ligne avec cet UUID quand le compte n'a pas d'identifiant de jeu
        assertEquals(UUID.fromString("6c0f9a22-392f-3b0d-a810-10c970feb01f"), MutedGroups.offlineUuid("One_Namikaze"));
    }

    @Test
    void groupeEcritEnConfig() {
        assertEquals("vote", MutedGroups.normalize(" Vote "));
        assertEquals("", MutedGroups.normalize(null));
        assertTrue(MutedGroups.GROUP.matcher("kit-nitro_2").matches());
        assertFalse(MutedGroups.GROUP.matcher("vote;drop").matches());
    }
}
