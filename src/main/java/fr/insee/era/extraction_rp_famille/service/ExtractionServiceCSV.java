package fr.insee.era.extraction_rp_famille.service;

import com.opencsv.CSVWriter;
import fr.insee.era.extraction_rp_famille.configuration.ParametrageConfiguration;
import fr.insee.era.extraction_rp_famille.dao.OdicDAO;
import fr.insee.era.extraction_rp_famille.dao.OmerDAO;
import fr.insee.era.extraction_rp_famille.model.BIEntity;
import fr.insee.era.extraction_rp_famille.model.Constantes;
import fr.insee.era.extraction_rp_famille.model.dto.ReponseListeUEDto;
import fr.insee.era.extraction_rp_famille.model.exception.CommuneInconnueException;
import fr.insee.era.extraction_rp_famille.model.exception.ConfigurationException;
import fr.insee.era.extraction_rp_famille.model.exception.RimInconnueException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.Date;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j @Service public class ExtractionServiceCSV {

        static List<String> HEADER_RECORD = new ArrayList<String>(
            Arrays.asList("Identifiant", "IdModele", "Internaute" , "IdLot", "CiviliteReferent", "NomReferent", "PrenomReferent", "MailReferent", "NumeroVoie",
                "IndiceRepetition", "TypeVoie", "LibelleVoie", "ComplementAdresse", "MentionSpeciale", "CodePostal", "LibelleCommune", "NomUe", "PrenomUe",
                "AnneeNaissanceUe", "TYPE_QUEST", "RPTYPEQUEST", "RPNBQUEST"));
        @Autowired OmerDAO omerDAO;
        @Autowired OdicDAO odicDAO;

        //Super moche mais évite de refactorer juste pour cette sortie CSV ou de dupliquer du code
        @Autowired ExtractionServiceJSON extractionServiceJSON;
        @Autowired ParametrageConfiguration parametrageProperties;

        public ByteArrayOutputStream getAllRimForPeriodAsCSV(Date dateDebut, Date dateFin, String questionnaireId)
            throws RimInconnueException, CommuneInconnueException, IOException, ConfigurationException {
                log.info("Extraction CSV entre dateDebut={} et dateFin={}",dateDebut,dateFin);
                //On doit adapter le header au nombre max de personnes et au nb max d'enfants
                updateHeader();

                ByteArrayOutputStream csvResultOutputStream= new ByteArrayOutputStream();

                //Récupération de la liste des UE à traiter
                Collection<ReponseListeUEDto> toutesLesRIM = extractionServiceJSON.getAllRimForPeriod(dateDebut,dateFin);
                int nbRimTraitées=0;
                try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(csvResultOutputStream))) {
                        //Ecriture du header CSV
                        writer.writeNext(HEADER_RECORD.toArray(new String[HEADER_RECORD.size()]));

                        //Traitement de chaque rim individuellement
                        for(ReponseListeUEDto rim : toutesLesRIM){
                                nbRimTraitées++;
                                if(  nbRimTraitées % 1000 == 1) {
                                        log.info("traitement de la RIM {} / {} ", nbRimTraitées, toutesLesRIM.size());
                                }
                                //Récupération des détails de la RIM
                                HashMap<Long, BIEntity> biEntityById = new HashMap<>();
                                HashMap<Long, Long> conjointByIndividuID = new HashMap<>();
                                LinkedMultiValueMap<Long, Long> inoutLienParentByIndividuId = new LinkedMultiValueMap<>();
                                LinkedMultiValueMap<Long, Long> inoutLienEnfantByIndividuId = new LinkedMultiValueMap<>();

                                List<BIEntity> biomer = omerDAO.getBiEtLiensForRim(rim.getId(),conjointByIndividuID,inoutLienParentByIndividuId,inoutLienEnfantByIndividuId);
                                List<BIEntity> biodic = odicDAO.getBiEtLiensForRim(rim.getId(),conjointByIndividuID,inoutLienParentByIndividuId,inoutLienEnfantByIndividuId);

                                for (var bi : biomer) {
                                        biEntityById.put(bi.getId(),bi);
                                }
                                for (var bi : biodic) {
                                        if(biEntityById.containsKey(bi.getId())){
                                                log.debug(String.format("bi[id=%s] existe dans OMER et ODIC; on garde la valeur OMER",bi.getId()));
                                        }
                                        else {
                                                biEntityById.put(bi.getId(), bi);
                                        }
                                }


                                var rimDetails  = omerDAO.getRim(rim.getId());
                                if( rimDetails== null){
                                        rimDetails=odicDAO.getRim(rim.getId());
                                }
                                if(rimDetails==null){
                                        throw new RimInconnueException(rim.getId());
                                }

                                Constantes.BI_SEXE sexe = parametrageProperties.getSexeForCommuneIris(rimDetails.getCodeCommune(),rimDetails.getIris());
                                if(sexe==null){
                                        throw new CommuneInconnueException(rimDetails.getCodeCommune(),rimDetails.getIris());
                                }


                                if (!biEntityById.isEmpty()) {

                                        //Déjà as on un pax majeur du bon sexe???
                                        List<BIEntity> biEnquetes =
                                            biEntityById.values().stream().filter(
                                                    biEntity ->
                                                        ( Integer.valueOf(biEntity.getAnai()) <= Constantes.ANNEE_NAISSANCE_MAJEUR
                                                            && biEntity.getSexe()==sexe
                                                        ))
                                                //On ne garde que le nombre max de personnes à enquêter
                                                .limit(Constantes.NB_MAX_PERSONNES_ENQUETEES)
                                                .collect(Collectors.toList());

                                        if(biEnquetes.isEmpty()){
                                                log.warn("BIid={} Pas de personne majeure de sexe={}",rim.getId(),sexe);
                                                continue;
                                        }


                                        String[] line = new String[HEADER_RECORD.size()];

                                        //Une seule ligne par logement
                                        int col = 0;
                                        line[col++] = String.valueOf(rim.getId()); //"Identifiant";
                                        line[col++] = questionnaireId; //"IdModele";
                                        line[col++] = rim.getInternaute(); //"Internaute
                                        line[col++] = null; //"IDLot";
                                        line[col++] = null; //"CiviliteReferent";
                                        line[col++] = null; //"NomReferent";
                                        line[col++] = null; //"PrenomReferent";
                                        line[col++] = rim.getMail(); //"MailReferent";
                                        line[col++] = rimDetails.getNumvoiloc();
                                        line[col++] = null; //"IndiceRepetition";
                                        line[col++] = rimDetails.getTypevoiloc();
                                        line[col++] = rimDetails.getNomvoiloc();
                                        line[col++] = null; //"ComplementAdresse";
                                        line[col++] = null; //"MentionSpeciale";
                                        line[col++] = rimDetails.getCpostloc();
                                        line[col++] = rimDetails.getCloc();

                                        line[col++] = null; //NomUe
                                        line[col++] = null; //PrenomUe
                                        line[col++] = null; //AnneeNaissanceUe

                                        line[col++] = sexe.toString();  // CSV : TYPE_QUEST
                                        line[col++] = sexe.toFullString(); //CSV : TYPE_QUEST
                                        line[col++] = String.valueOf(biEnquetes.size()); //RPNBQUEST

                                        //Ensuite on écrit N fois la liste des prénoms
                                        //Suivis par d'éventuelles colonnes vides  (pour les familles avec moins de personnes concernées)
                                        String listePrenom = String.join(", ",biEnquetes.stream().map(BIEntity::getPrenom).collect(Collectors.toList()));
                                        int i = 0;
                                        do {
                                                if (i < biEnquetes.size()) {
                                                        line[col++] = listePrenom;
                                                }
                                                else {
                                                        line[col++] = "";
                                                }
                                                i++;
                                        }
                                        while (i < Constantes.NB_MAX_PERSONNES_ENQUETEES);

                                        //Ensuite on traite chaque personne
                                        int compteur = 0;
                                        for (BIEntity bi : biEntityById.values()) {
                                                //On ne garde que les majeurs du bon sexe
                                                if(!biEnquetes.contains(bi)){
                                                        continue;
                                                }

                                                compteur++;
                                                //Conjoint
                                                var conjointId = conjointByIndividuID.get(bi.getId());
                                                if (conjointId == null) {
                                                        line[col++] = null; //"" + compteur + "PAS_DE_CONJOINT";
                                                        line[col++] = null; //"" + compteur + "PAS_DE_CONJOINT";
                                                        line[col++] = null; //"" + compteur + "PAS_DE_CONJOINT";

                                                }
                                                else {
                                                        BIEntity conjoint = biEntityById.get(conjointId);
                                                        line[col++] = conjoint.getPrenom();
                                                        line[col++] = conjoint.getSexe().toString();
                                                        line[col++] = conjoint.getAnai();
                                                }

                                                //Parents
                                                //parents de l'individu (triés du plus vieux au plus jeune : règle métier)
                                                //TODO : mettre un TU sur le tri
                                                List<Long> parents = inoutLienEnfantByIndividuId.get(bi.getId());
                                                BIEntity parent1=null;
                                                BIEntity parent2 = null;

                                                if(parents!=null){
                                                        if(parents.size()>=2){
                                                                BIEntity parentA = biEntityById.get(parents.get(0));
                                                                BIEntity parentB = biEntityById.get(parents.get(1));
                                                                if(Integer.valueOf(parentA.getAnai())>Integer.valueOf(parentB.getAnai())){
                                                                        parent1 = parentA;
                                                                        parent2= parentB;
                                                                }
                                                                else{
                                                                        parent1=parentB;
                                                                        parent2=parentA;
                                                                }
                                                        }
                                                        else if(parents.size()==1){
                                                                parent1 = biEntityById.get(parents.get(0));
                                                        }
                                                }

                                                if (parent1 == null) {
                                                        line[col++] = null;
                                                        line[col++] = null;
                                                        line[col++] = null;
                                                        line[col++] = null;
                                                        line[col++] = null;
                                                        line[col++] = null;
                                                }
                                                else{
                                                        line[col++] = parent1.getPrenom();
                                                        line[col++] = parent1.getSexe().toString();
                                                        line[col++] = parent1.getAnai();
                                                        if(parent2==null){
                                                                line[col++] = null;
                                                                line[col++] = null;
                                                                line[col++] = null;
                                                        }
                                                        else{
                                                                line[col++] = parent2.getPrenom();
                                                                line[col++] = parent2.getSexe().toString();
                                                                line[col++] = parent2.getAnai();
                                                        }
                                                }

                                                //ENFANTS
                                                var enfants = inoutLienParentByIndividuId.get(bi.getId());
                                                int nbEnfants=(enfants==null)?0:enfants.size();
                                                for (int j=0; j< Constantes.NB_MAX_ENFANT_PAR_PERSONNE; j++){
                                                        //TODO: faire un writer pour une entité de 3 champs comme pour le json

                                                        if(j<nbEnfants){
                                                                BIEntity enfantEntity = biEntityById.get(enfants.get(j));
                                                                if(enfantEntity==null)
                                                                {
                                                                        int ttt=0;
                                                                        ttt++;
                                                                }
                                                                line[col++] = enfantEntity.getPrenom();
                                                                line[col++] = enfantEntity.getSexe().toString();
                                                                line[col++] = enfantEntity.getAnai();
                                                        }
                                                        else{
                                                                line[col++] = null;
                                                                line[col++] = null;
                                                                line[col++] = null;
                                                        }
                                                }
                                        }
                                        writer.writeNext(line);
                                }
                                else {
                                        log.warn("Pas de BI pour idRim=" + rim.getId());
                                }
                        }
                }

                return csvResultOutputStream;
        }

        private static void updateHeader (){

                //ajout des RPLISTEPRENOMS_1...N au header
                for (long i = 1; i <= Constantes.NB_MAX_PERSONNES_ENQUETEES; i++) {
                        HEADER_RECORD.add("RPLISTEPRENOMS_" + i);
                }

                //ajout des entêtes spécifiques
                for (long i = 1; i <= Constantes.NB_MAX_PERSONNES_ENQUETEES; i++) {
                        //Conjoint
                        HEADER_RECORD.add("RPPRENOMCONJ_" + i);
                        HEADER_RECORD.add("RPSEXCONJ_" + i);
                        HEADER_RECORD.add("RPANAISCONJ_" + i);

                        //parent1
                        HEADER_RECORD.add("RPPRENOMPAR1_" + i);
                        HEADER_RECORD.add("RPSEXPAR1_" + i);
                        HEADER_RECORD.add("RPANAISPAR1_" + i);

                        //parent2
                        HEADER_RECORD.add("RPPRENOMPAR2_" + i);
                        HEADER_RECORD.add("RPSEXPAR2_" + i);
                        HEADER_RECORD.add("RPANAISPAR2_" + i);

                        //Enfants
                        for (long j = 1; j <= Constantes.NB_MAX_ENFANT_PAR_PERSONNE; j++) {
                                HEADER_RECORD.add("RPPRENOMENF" + j + "_" + i);
                                HEADER_RECORD.add("RPSEXENF" + j + "_" + i);
                                HEADER_RECORD.add("RPANAISENF" + j + "_" + i);
                        }
                }
        }
}