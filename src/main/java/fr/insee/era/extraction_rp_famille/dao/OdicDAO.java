package fr.insee.era.extraction_rp_famille.dao;

import fr.insee.era.extraction_rp_famille.configuration.OdicDataSourceConfiguration;
import fr.insee.era.extraction_rp_famille.model.BDDSource;
import fr.insee.era.extraction_rp_famille.model.BIEntity;
import fr.insee.era.extraction_rp_famille.model.Constantes;
import fr.insee.era.extraction_rp_famille.model.dto.*;
import fr.insee.era.extraction_rp_famille.model.enums.GenderType;
import fr.insee.era.extraction_rp_famille.model.exception.ConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class OdicDAO extends CommonDAO {

    OdicDAO() {
        bddSource = BDDSource.ODIC;
    }

    @Autowired
    private OdicDataSourceConfiguration conf;

    @Qualifier("odicJdbcTemplate")
    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<ReponseListeUEDto> getIdRIMetInternetForPeriod(Date dateDebut, Date dateFin, Constantes.BI_SEXE sexe) throws ConfigurationException {
        log.info("getIdRIMetInternetForPeriod ODIC");

        JdbcTemplate jdbcTemplate1 = new JdbcTemplate(conf.odicDataSource());

        return getIdRIMetInternetForPeriod(dateDebut, dateFin, jdbcTemplate1, sexe);
    }

    public List<BIEntity> getBiEtLiensForRim(
            Long rimId,
            Map<Long, Long> inoutConjointByIndividuID,
            LinkedMultiValueMap<Long, Long> inoutLienParentByIndividuId,
            LinkedMultiValueMap<Long, Long> inoutLienEnfantByIndividuId) {
        JdbcTemplate jdbcTemplate1 = new JdbcTemplate(conf.odicDataSource());

        return getBiEtLiensForRim(rimId, jdbcTemplate1, inoutConjointByIndividuID, inoutLienParentByIndividuId,
                inoutLienEnfantByIndividuId);
    }

    @Override
    public RIMDto getRim(Long rimId) {

        JdbcTemplate jdbcTemplate1 = new JdbcTemplate(conf.odicDataSource());

        return getRim(rimId, jdbcTemplate1);
    }

    public void createTemporaryTableForCitiesParameter(List<CityParameterDto> citiesParameterEntities) {
        createTemporaryTableForCitiesParameter(jdbcTemplate, citiesParameterEntities);
    }

    public List<ResponseNetUserDto> getRimByGenderCityAndPeriod(GenderType gender, LocalDate startDate,
                                                                LocalDate endDate) {
        return getRimByGenderCityAndPeriod(gender, startDate, endDate, jdbcTemplate);
    }

    public Map<Long,List<IndividualFormDto>> getIndividuals(List<Long> ids) {
        return getIndividuals(ids, jdbcTemplate);
    }

}
