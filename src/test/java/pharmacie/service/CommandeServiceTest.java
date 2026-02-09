package pharmacie.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import pharmacie.dao.CommandeRepository;
import pharmacie.dao.LigneRepository;
import pharmacie.dao.MedicamentRepository;
import pharmacie.entity.Commande;
import pharmacie.entity.Ligne;
import pharmacie.entity.Medicament;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class CommandeServiceTest {

    @Autowired
    private CommandeService service;
    @Autowired
    private MedicamentRepository medicamentDao;
    @Autowired
    private LigneRepository ligneDao;
    @Autowired
    private CommandeRepository commandeDao;

    // Données de test : commande 99998 (non envoyée) contient une ligne pour medicament 98 (quantite 16)
    // commande 99999 est déjà envoyée

    @Test
    void ajouterLigne_happyPath_creeNouvelleLigneEtMetAJourMedicament() {
        // medicament 93 est disponible et a 100 en stock, 0 en commande
        Medicament med = medicamentDao.findById(93).orElseThrow();
        int beforeCommandees = med.getUnitesCommandees();

        Ligne ligne = service.ajouterLigne(99998, 93, 5);
        assertNotNull(ligne.getId());
        Medicament medAfter = medicamentDao.findById(93).orElseThrow();
        assertEquals(beforeCommandees + 5, medAfter.getUnitesCommandees());
    }

    @Test
    void ajouterLigne_existingLigne_addsQuantite() {
        // commande 99998 already has medicament 98 with quantity 16
        Medicament med = medicamentDao.findById(98).orElseThrow();
        int beforeCommandees = med.getUnitesCommandees();

        Ligne ligne = service.ajouterLigne(99998, 98, 4);
        // after, the same ligne should have increased quantity
        var opt = ligneDao.findByCommandeAndMedicament(commandeDao.findById(99998).orElseThrow(), med);
        assertTrue(opt.isPresent());
        assertEquals(16 + 4, opt.get().getQuantite());
        Medicament medAfter = medicamentDao.findById(98).orElseThrow();
        assertEquals(beforeCommandees + 4, medAfter.getUnitesCommandees());
    }

    @Test
    void ajouterLigne_commandeDejaEnvoyee_throws() {
        assertThrows(IllegalStateException.class, () -> service.ajouterLigne(99999, 93, 1));
    }

    @Test
    void ajouterLigne_medicamentIndisponible_throws() {
        assertThrows(IllegalStateException.class, () -> service.ajouterLigne(99998, 97, 1));
    }

    @Test
    void ajouterLigne_notEnoughStock_throws() {
        // medicament 98 has en_stock 26 and unitesCommandees 20 in test data => available = 6
        assertThrows(IllegalStateException.class, () -> service.ajouterLigne(99998, 98, 10));
    }

    @Test
    void supprimerLigne_happyPath_decrementeMedicamentEtSupprimeLigne() {
        // create a temp line to delete
        Ligne ligne = service.ajouterLigne(99998, 93, 2);
        Medicament med = medicamentDao.findById(93).orElseThrow();
        int beforeCommandees = med.getUnitesCommandees();

        service.supprimerLigne(ligne.getId());
        Medicament medAfter = medicamentDao.findById(93).orElseThrow();
        assertEquals(beforeCommandees - 2, medAfter.getUnitesCommandees());
        assertFalse(ligneDao.findById(ligne.getId()).isPresent());
    }

    @Test
    void supprimerLigne_commandeEnvoyee_throws() {
        // ligne 1 belongs to commande 99999 (already shipped) per test data
        // we need to find a line id for commande 99999
        Commande c = commandeDao.findById(99999).orElseThrow();
        var lignes = ligneDao.findByCommande(c);
        assertTrue(lignes.size() > 0);
        int id = lignes.get(0).getId();
        assertThrows(IllegalStateException.class, () -> service.supprimerLigne(id));
    }

    @Test
    void enregistreExpedition_happyPath_decrementeStockEtCommandeesEtMetDate() {
        // Prepare: ensure medicament 93 has enough stock
        Medicament med = medicamentDao.findById(93).orElseThrow();
        med.setUnitesEnStock(100);
        med.setUnitesCommandees(0);
        medicamentDao.save(med);

        // add a line to commande 99998
        Ligne ligne = service.ajouterLigne(99998, 93, 3);
        Commande c = service.enregistreExpedition(99998);
        assertNotNull(c.getEnvoyeele());
        Medicament medAfter = medicamentDao.findById(93).orElseThrow();
        assertEquals(100 - 3, medAfter.getUnitesEnStock());
        assertEquals(0, medAfter.getUnitesCommandees());
    }

    @Test
    void enregistreExpedition_notEnoughStock_throwsAndNoChange() {
        // Prepare medicament 99 with enough stock to add the line
        Medicament med = medicamentDao.findById(99).orElseThrow();
        med.setUnitesEnStock(10);
        med.setUnitesCommandees(0);
        medicamentDao.save(med);

        // add a line with quantity 5 to commande 99998 (should succeed)
        Ligne ligne = service.ajouterLigne(99998, 99, 5);

        // Simulate external stock change: now stock is only 1, so expedition must fail
        med.setUnitesEnStock(1);
        medicamentDao.save(med);

        // attempt to ship should fail
        assertThrows(IllegalStateException.class, () -> service.enregistreExpedition(99998));
        // verify no change to stock/commandees for medicament 99 (still 1 and commandes still reflect the added line)
        Medicament medAfter = medicamentDao.findById(99).orElseThrow();
        assertEquals(1, medAfter.getUnitesEnStock());
        // cleanup: remove the line
        service.supprimerLigne(ligne.getId());
    }
}
