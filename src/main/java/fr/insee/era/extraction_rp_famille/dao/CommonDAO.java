package fr.insee.era.extraction_rp_famille.dao;

import fr.insee.era.extraction_rp_famille.configuration.ParametrageConfiguration;
import fr.insee.era.extraction_rp_famille.model.BDDSource;
import fr.insee.era.extraction_rp_famille.model.BIEntity;
import fr.insee.era.extraction_rp_famille.model.Constantes;
import fr.insee.era.extraction_rp_famille.model.LogementIndividu;
import fr.insee.era.extraction_rp_famille.model.dto.*;
import fr.insee.era.extraction_rp_famille.model.enums.GenderType;
import fr.insee.era.extraction_rp_famille.model.enums.RelationshipType;
import fr.insee.era.extraction_rp_famille.model.exception.ConfigurationException;
import fr.insee.era.extraction_rp_famille.model.mapper.BIEntityMapper;
import fr.insee.era.extraction_rp_famille.model.mapper.RIMDtoMapper;
import fr.insee.era.extraction_rp_famille.model.mapper.ReponseListeUEDtoMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
public abstract class CommonDAO {

    BDDSource bddSource; // A définir dans les sous classes
    @Autowired
    ParametrageConfiguration parametrageProperties;

    //-------------------------------------------------POUR JSON et
    // COLEMAN----------------------------------------------------------//
    public abstract List<ReponseListeUEDto> getIdRIMetInternetForPeriod(Date dateDebut, Date dateFin,
                                                                        Constantes.BI_SEXE sexe) throws ConfigurationException;

    public abstract List<BIEntity> getBiEtLiensForRim(
            Long rimId,
            Map<Long, Long> inoutConjointByIndividuID,
            LinkedMultiValueMap<Long, Long> inoutLienParentByIndividuId,
            LinkedMultiValueMap<Long, Long> inoutLienEnfantByIndividuId);

    /**
     * Récupération des infos d'une RIM en base
     *
     * @param rimId
     * @return le DTO ou Null si rimId n'existe pas en base
     */
    public abstract RIMDto getRim(Long rimId);

    protected RIMDto getRim(Long rimId, JdbcTemplate jdbc) {

        try {

            return jdbc.queryForObject("SELECT numvoiloc,typevoiloc,nomvoiloc,cpostloc,cloc "
                    + ", rim.identifiant , codedepartement||codecommune as code_commune_complet, irisar, mail "
                    + "  FROM reponseinternetmenages rim, internautes i "
                    + " where rim.idinternaute  = i.id and rim.id=?", new RIMDtoMapper(), rimId);


        } catch (EmptyResultDataAccessException e) {
            //La RIM n'existe pas dans cette base
            return null;
        }
    }


    protected List<ReponseListeUEDto> getIdRIMetInternetForPeriod(Date dateDebut, Date dateFin, JdbcTemplate jdbc,
                                                                  Constantes.BI_SEXE sexe) throws ConfigurationException {

        jdbc.execute("DROP TABLE IF EXISTS tmp_era_communes_a_traiter_par_sexe ");
        jdbc.execute("CREATE TEMPORARY TABLE IF NOT EXISTS tmp_era_communes_a_traiter_par_sexe (code_commune_insee " +
                "varchar(255) NOT NULL,  irisar varchar(255), sexe varchar(255) NOT NULL) ");

        List<Object[]> communesSexe = new ArrayList<>();
        if (sexe == null || sexe.equals(Constantes.BI_SEXE.BI_SEXE_HOMME)) {
            for (String id : parametrageProperties.getCommunesHommes()) {
                communesSexe.add(new Object[]{id, null, Constantes.BI_SEXE.BI_SEXE_HOMME.toString()});
            }
            for (String irisHomme : parametrageProperties.getIrisHommes()) {
                var splittedData = irisHomme.split("-");
                if (splittedData.length != 2) {
                    String message = "La conf d'un des iris homme n'est pas du type <codeCommune>-<IRIS>";
                    log.error(message);
                    throw new ConfigurationException(message);
                }
                communesSexe.add(new Object[]{splittedData[0], splittedData[1],
                        Constantes.BI_SEXE.BI_SEXE_HOMME.toString()});
            }
        }
        if (sexe == null || sexe.equals(Constantes.BI_SEXE.BI_SEXE_FEMME)) {
            for (String id : parametrageProperties.getCommunesFemmes()) {
                communesSexe.add(new Object[]{id, null, Constantes.BI_SEXE.BI_SEXE_FEMME.toString()});
            }
            for (String irisFemme : parametrageProperties.getIrisFemmes()) {
                var splittedData = irisFemme.split("-");
                if (splittedData.length != 2) {
                    String message = "La conf d'un des iris femme n'est pas du type <codeCommune>-<IRIS>";
                    log.error(message);
                    throw new ConfigurationException(message);
                }
                communesSexe.add(new Object[]{splittedData[0], splittedData[1],
                        Constantes.BI_SEXE.BI_SEXE_FEMME.toString()});
            }
        }


        log.info("Insertion dans la table temporaire des communes pour sexe={}", ((sexe == null) ? "hommes&femmes" :
                sexe.toFullString()));
        log.info("Configuration Communes/IRIS : {}", communesSexe.stream()
                .map(objects -> String.format("codeCommune=%s irisar=%s sexe=%s", objects[0], objects[1], objects[2]))
                .collect(Collectors.joining(","))
        );
        jdbc.batchUpdate("INSERT INTO tmp_era_communes_a_traiter_par_sexe VALUES(?,?,?)", communesSexe);
        log.info("Recuperation des RIMs (ayant un mail renseigné)");
        String sql =
                "select distinct r.id,r.identifiant,  "
                        //+ " b.sexe,"
                        + " tmp.sexe, "
                        + " regexp_replace(i.mail, '\\s', '', 'g')  mail"
                        + " from   reponseinternetmenages r, "
                        //+ " bulletinindividuels b ,"
                        + "  internautes i, tmp_era_communes_a_traiter_par_sexe tmp "
                        + " where   r.dateenvoi between ? and ? " + " and     r.codedepartement||r.codecommune  = tmp" +
                        ".code_commune_insee "
                        + " and (tmp.irisar is null or tmp.irisar=r.irisar) "
                        //+ " and     b.feuillelogement = r.id " + " and     b.tableauabcd='A' " + " and     b.sexe =
                        // tmp.sexe " + " and     b.anai <= '"
                        //+ Constantes.ANNEE_NAISSANCE_MAJEUR + "' "
                        + " and r.idinternaute  = i.id "
                        // enlever les personnes sans mail car pour famille on ne peut rien en faire
                        + " and (LTRIM(i.mail) <> '')  ";

        Timestamp debutTS = new Timestamp(dateDebut.getTime());
        Timestamp finTS = new Timestamp(dateFin.getTime());

        log.info("Récupération des rims entre " + debutTS + " et " + finTS);
        var list1 = jdbc.query(sql, new Object[]{debutTS, finTS}, new int[]{Types.TIMESTAMP, Types.TIMESTAMP},
                new ReponseListeUEDtoMapper());

        jdbc.execute("TRUNCATE tmp_era_communes_a_traiter_par_sexe");
        return list1;
    }


    protected List<BIEntity> getBiEtLiensForRim(
            Long rimId,
            JdbcTemplate jdbcTemplate,
            Map<Long, Long> inoutConjointByIndividuID,
            LinkedMultiValueMap<Long, Long> inoutLienParentByIndividuId,
            LinkedMultiValueMap<Long, Long> inoutLienEnfantByIndividuId) {

        log.debug("Lecture en base pour RIM " + rimId);

        String sql =
                "select id, nom, prenom, sexe, anai "
                        + " from bulletinindividuels "
                        + " where tableauabcd ='A' and feuillelogement=? ";
        List<BIEntity> bIEntityList = jdbcTemplate.query(sql, new BIEntityMapper(bddSource), rimId);

        List<Long> biIdList = bIEntityList.stream().map(BIEntity::getId).toList();
        if (!biIdList.isEmpty()) {
            String inParams = String.join(",", biIdList.stream().map(id -> "?").toList());

            String sqlLien =
                    String.format("SELECT individu, lienenregistre, individurelie " + " FROM lienindividus " + " " +
                            "WHERE lienenregistre in (1,2,3)"
                            + "        AND ( individu IN (%s) and individurelie in (%s) ) ", inParams, inParams);

            List<Long> liste2X = biIdList;
            liste2X.addAll(biIdList);
            var liensList = jdbcTemplate.query(sqlLien, (rs, i) -> Triple.of(rs.getLong(1), rs.getLong(2),
                            rs.getLong(3)),
                    liste2X.toArray(Long[]::new));

            for (Triple<Long, Long, Long> lien : liensList) {
                Long middle = lien.getMiddle();
                if (middle.equals(Constantes.LIEN_CONJOINT)) {
                    //Si on a déjà un lien conjoint ==> On ignore les suivants de cet individu
                    inoutConjointByIndividuID.putIfAbsent(lien.getLeft(), lien.getRight());
                } else if (middle.equals(Constantes.LIEN_PARENT)) {
                    //individu est le parent de individurelie
                    inoutLienParentByIndividuId.add(lien.getLeft(), lien.getRight());
                } else if (middle.equals(Constantes.LIEN_ENFANT)) {
                    //individu est un enfant de individurelie
                    inoutLienEnfantByIndividuId.add(lien.getLeft(), lien.getRight());
                }
            }

            //suppression des liens où on n'a pas le mirroir
            //Conjoint
            inoutConjointByIndividuID.entrySet().removeIf(
                    entry ->
                    {
                        Long conjointId = inoutConjointByIndividuID.get(entry.getKey());
                        Long conjointDuConjoint = inoutConjointByIndividuID.get(conjointId);

                        return !entry.getKey().equals(conjointDuConjoint);
                    });

            nettoyageLiensParentsEnfants(inoutLienEnfantByIndividuId, inoutLienParentByIndividuId);
            nettoyageLiensParentsEnfants(inoutLienParentByIndividuId, inoutLienEnfantByIndividuId);

            //Enfant (un enfant peut indiquer deux relations seuleulement)
            for (Long idEnfant : new ArrayList<>(inoutLienEnfantByIndividuId.keySet())) {
                var parentList = inoutLienEnfantByIndividuId.get(idEnfant);
                if (parentList.size() > 2) {
                    List<Long> idParentsASupprimer = new ArrayList<>(parentList.subList(2, parentList.size()));
                    for (Long idParent : idParentsASupprimer) {
                        log.debug("Individu avec plus que deux parents. Suppression liens entre individu=" + idEnfant
                                + " et individu=" + idParent);
                        inoutLienParentByIndividuId.get(idParent).remove(idEnfant);
                        inoutLienEnfantByIndividuId.get(idEnfant).remove(idParent);
                    }
                }
            }
        }
        return bIEntityList;
    }


    private void nettoyageLiensParentsEnfants(LinkedMultiValueMap<Long, Long> mapA,
                                              LinkedMultiValueMap<Long, Long> mapB) {
        //Pair :
        //      Left  : Key dans mapA
        //      Right : Value de mapA(left) à supprimer de la liste
        List<Pair<Long, Long>> liensASupprimer = new LinkedList<>();
        for (Long individuId : new ArrayList<>(mapA.keySet())) {
            for (Long individuRelieId : mapA.get(individuId)) {
                if (!mapB.containsKey(individuRelieId) || !mapB.get(individuRelieId).contains(individuId)) {
                    log.info("Suppression du lien orphelin individu=" + individuId + " individurelie=" + individuRelieId);
                    liensASupprimer.add(new ImmutablePair<>(individuId, individuRelieId));
                }
            }
        }

        for (Pair<Long, Long> lienASupprimer : liensASupprimer) {
            mapA.get(lienASupprimer.getLeft()).remove(lienASupprimer.getRight());
        }
    }

    protected void createTemporaryTableForCitiesParameter(JdbcTemplate jdbc,
                                                          List<CityParameterDto> citiesParameter) {
        jdbc.execute("DROP TABLE IF EXISTS city_parameter_tmp");
        jdbc.execute("CREATE TEMPORARY TABLE IF NOT EXISTS city_parameter_tmp (city_code " +
                "varchar(255) NOT NULL,  iris varchar(255), gender varchar(1) NOT NULL)");

        jdbc.batchUpdate("INSERT INTO city_parameter_tmp (city_code, iris, gender) VALUES(?, ?, ?)"
                , citiesParameter, 100, (PreparedStatement ps, CityParameterDto cityParameter) -> {
                    ps.setString(1, cityParameter.getCityCode());
                    ps.setString(2, cityParameter.getIris());
                    ps.setString(3, cityParameter.getGender().getValue());
                });
    }

    protected List<ResponseNetUserDto> getRimByGenderCityAndPeriod(GenderType gender, LocalDate startDate,
                                                                   LocalDate endDate, JdbcTemplate jdbc) {
        String sql = "select r.id, r.identifiant, r.idinternaute, regexp_replace(mail, '[\\s+]', '', 'g') as mail, CONCAT(codedepartement, codecommune) as " +
                "depcom , irisar," +
                "numvoiloc, bisterloc, typevoiloc, nomvoiloc, resloc, cpostloc, car " +
                "from reponseinternetmenages r, city_parameter_tmp tmp, internautes i " +
                "where dateenvoi between ? and ? " +
                "and i.id = r.idinternaute " +
                "and i.mail is not null " +
                "and CONCAT(codedepartement, codecommune) = tmp.city_code " +
                "and (tmp.iris is null or tmp.iris=r.irisar) " +
                "and tmp.gender = ? " +
                "order by r.id";
        var startTimeStamp = Timestamp.valueOf(startDate.atStartOfDay());
        var endTimeStamp = Timestamp.valueOf(endDate.atStartOfDay());
        List<ResponseNetUserDto> responses = jdbc.query(sql, new Object[]{startTimeStamp, endTimeStamp,
                        gender.getValue()},
                new int[]{Types.TIMESTAMP, Types.TIMESTAMP, Types.VARCHAR},
                (rs, rowNum) -> ResponseNetUserDto.builder()
                        .id(rs.getLong("id"))
                        .identifiant(rs.getString("identifiant"))
                        .idinternaute(rs.getLong("idinternaute"))
                        .mail(rs.getString("mail"))
                        .depcom(rs.getString("depcom"))
                        .iris(rs.getString("irisar"))
                        .numvoiloc(rs.getString("numvoiloc"))
                        .bisterloc(rs.getString("bisterloc"))
                        .typevoiloc(rs.getString("typevoiloc"))
                        .nomvoiloc(rs.getString("nomvoiloc"))
                        .resloc(rs.getString("resloc"))
                        .cpostloc(rs.getString("cpostloc"))
                        .car(rs.getString("car"))
                        .build());
        return responses;
    }

    protected Map<Long, List<IndividualFormDto>> getIndividuals(List<Long> ids, JdbcTemplate jdbc) {
        String sqlIndividu = "select feuillelogement, id, nom, prenom, anai, mnai, jnai, dpnaicode, cnaif, " +
                "pnai, sexe, " +
                "nai_p1, pnai_p1, nai_p2, pnai_p2, null as lienenregistre, null as individurelie " +
                "from bulletinindividuels b " +
                "where tableauabcd = 'A' and feuillelogement in (SELECT id FROM response_id_tmp) " +
                "order by b.feuillelogement, b.id ";

        String sqlTies = "select feuillelogement, id, lienenregistre, individurelie " +
                "from bulletinindividuels b left join lienindividus l on l.individu = b.id " +
                "where tableauabcd = 'A' and feuillelogement in (SELECT id FROM response_id_tmp) and lienenregistre " +
                "in (1,2,3)" +
                "order by b.feuillelogement, b.id ";

        createTemporaryTableForResponseIds(ids, jdbc);
        List<Map<String, Object>> individuals = jdbc.queryForList(sqlIndividu);
        List<Map<String, Object>> ties = jdbc.queryForList(sqlTies);

        if (individuals.isEmpty()) {
            return new HashMap<>();
        }

        Map<LogementIndividu, Map<String, Object>> resultsByLogementAndIndividu = individuals.stream()
                .collect(Collectors.toMap(i -> new LogementIndividu((Long) i.get("feuillelogement"),
                        (Long) i.get("id")), i -> i));
        Map<LogementIndividu, List<RelationshipDto>> tiesByLogementAndIndividu = new HashMap<>();
        for (Map<String, Object> oneTies : ties) {
            LogementIndividu logementIndividu = new LogementIndividu((Long) oneTies.get("feuillelogement"), (Long) oneTies.get("id"));
            Long idRelationship = (Long) oneTies.get("individurelie");
            RelationshipType relationshipType = RelationshipType.fromValue((Integer) oneTies.get(
                    "lienenregistre"));
            RelationshipDto oneRelationship = RelationshipDto.builder()
                    .idRelationship(idRelationship)
                    .relationshipType(relationshipType)
                    .build();
            List<RelationshipDto> relationships = tiesByLogementAndIndividu.get(logementIndividu);
            if (relationships == null) {
                relationships = new ArrayList<>();
            }
            relationships.add(oneRelationship);
            tiesByLogementAndIndividu.put(logementIndividu, relationships);
        }


        Map<Long, List<IndividualFormDto>> individualsByFeuillelogementMap = new HashMap<>();
        for (LogementIndividu logementIndividu : resultsByLogementAndIndividu.keySet()) {
            Map<String, Object> individualForThisFeuillelogement =
                    resultsByLogementAndIndividu.get(logementIndividu);
            List<RelationshipDto> relationships = tiesByLogementAndIndividu.get(logementIndividu);
            if (relationships == null) {
                relationships = new ArrayList<>();
            }
            IndividualFormDto individualFormDto = mapIndividualFormDto(individualForThisFeuillelogement, relationships);
            List<IndividualFormDto> individualFormDtos =
                    individualsByFeuillelogementMap.get(logementIndividu.idLogement());
            if (individualFormDtos == null) {
                individualFormDtos = new ArrayList<>();
            }
            individualFormDtos.add(individualFormDto);
            individualsByFeuillelogementMap.put(logementIndividu.idLogement(), individualFormDtos);
        }

        jdbc.execute("Truncate response_id_tmp");
        return individualsByFeuillelogementMap;
    }

    private IndividualFormDto mapIndividualFormDto(Map<String, Object> individualForThisFeuillelogement,
                                                   List<RelationshipDto> ties) {

        Long individualId = (Long) individualForThisFeuillelogement.get("id");

        return IndividualFormDto.builder()
                .id(individualId)
                .lastName((String) individualForThisFeuillelogement.get("nom"))
                .firstName((String) individualForThisFeuillelogement.get("prenom"))
                .gender((String) individualForThisFeuillelogement.get("sexe"))
                .birthYear((String) individualForThisFeuillelogement.get("anai"))
                .birthMonth((String) individualForThisFeuillelogement.get("mnai"))
                .birthDay((String) individualForThisFeuillelogement.get("jnai"))
                .dpnaicode((String) individualForThisFeuillelogement.get("dpnaicode"))
                .cnaif((String) individualForThisFeuillelogement.get("cnaif"))
                .pnai((String) individualForThisFeuillelogement.get("pnai"))
                .naiP1((String) individualForThisFeuillelogement.get("nai_p1"))
                .paysNaiP1((String) individualForThisFeuillelogement.get("pnai_p1"))
                .naiP2((String) individualForThisFeuillelogement.get("nai_p2"))
                .paysNaiP2((String) individualForThisFeuillelogement.get("pnai_p2"))
                .familyTies(ties)
                .build();
    }

    private void createTemporaryTableForResponseIds(List<Long> ids, JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS response_id_tmp");
        jdbc.execute("CREATE TEMPORARY TABLE IF NOT EXISTS response_id_tmp (id bigint NOT NULL)");
        List<Object[]> responseIds = new ArrayList<>();
        for (Long id : ids) {
            responseIds.add(new Object[]{id});
        }
        jdbc.batchUpdate("INSERT INTO response_id_tmp VALUES(?)", responseIds);
    }
}