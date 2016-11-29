/*
 * Copyright (C) 2012-2016 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *     - Apache License, version 2.0
 *     - Apache Software License, version 1.0
 *     - GNU Lesser General Public License, version 3
 *     - Mozilla Public License, versions 1.0, 1.1 and 2.0
 *     - Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public
 * License version 2 and the aforementioned licenses.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */
package org.n52.sos.ds.hibernate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.n52.iceland.convert.ConverterException;
import org.n52.iceland.ds.ConnectionProvider;
import org.n52.iceland.exception.ows.concrete.NotYetSupportedException;
import org.n52.iceland.i18n.LocaleHelper;
import org.n52.iceland.ogc.ows.ServiceMetadataRepository;
import org.n52.iceland.ogc.sos.ConformanceClasses;
import org.n52.iceland.service.ServiceConfiguration;
import org.n52.iceland.util.LocalizedProducer;
import org.n52.janmayen.http.HTTPStatus;
import org.n52.shetland.ogc.gml.time.IndeterminateValue;
import org.n52.shetland.ogc.om.OmObservation;
import org.n52.shetland.ogc.om.OmObservationConstellation;
import org.n52.shetland.ogc.ows.OwsServiceProvider;
import org.n52.shetland.ogc.ows.exception.CodedException;
import org.n52.shetland.ogc.ows.exception.NoApplicableCodeException;
import org.n52.shetland.ogc.ows.exception.OwsExceptionReport;
import org.n52.shetland.ogc.sos.Sos1Constants;
import org.n52.shetland.ogc.sos.Sos2Constants;
import org.n52.shetland.ogc.sos.SosConstants;
import org.n52.shetland.util.CollectionHelper;
import org.n52.sos.ds.AbstractGetObservationHandler;
import org.n52.sos.ds.FeatureQueryHandler;
import org.n52.sos.ds.hibernate.dao.DaoFactory;
import org.n52.sos.ds.hibernate.dao.FeatureOfInterestDAO;
import org.n52.sos.ds.hibernate.dao.observation.AbstractObservationDAO;
import org.n52.sos.ds.hibernate.dao.observation.legacy.LegacyObservationDAO;
import org.n52.sos.ds.hibernate.dao.observation.series.AbstractSeriesDAO;
import org.n52.sos.ds.hibernate.dao.observation.series.AbstractSeriesObservationDAO;
import org.n52.sos.ds.hibernate.entities.EntitiyHelper;
import org.n52.sos.ds.hibernate.entities.FeatureOfInterest;
import org.n52.sos.ds.hibernate.entities.ObservationConstellation;
import org.n52.sos.ds.hibernate.entities.observation.Observation;
import org.n52.sos.ds.hibernate.entities.observation.series.Series;
import org.n52.sos.ds.hibernate.entities.observation.series.SeriesObservation;
import org.n52.sos.ds.hibernate.util.HibernateGetObservationHelper;
import org.n52.sos.ds.hibernate.util.QueryHelper;
import org.n52.sos.ds.hibernate.util.observation.HibernateObservationUtilities;
import org.n52.sos.ds.hibernate.values.HibernateChunkStreamingValue;
import org.n52.sos.ds.hibernate.values.HibernateScrollableStreamingValue;
import org.n52.sos.ds.hibernate.values.HibernateStreamingConfiguration;
import org.n52.sos.ds.hibernate.values.HibernateStreamingValue;
import org.n52.sos.ds.hibernate.values.series.HibernateChunkSeriesStreamingValue;
import org.n52.sos.ds.hibernate.values.series.HibernateScrollableSeriesStreamingValue;
import org.n52.sos.ds.hibernate.values.series.HibernateSeriesStreamingValue;
import org.n52.sos.exception.ows.concrete.MissingObservedPropertyParameterException;
import org.n52.sos.request.GetObservationRequest;
import org.n52.sos.response.GetObservationResponse;
import org.n52.sos.service.profile.ProfileHandler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Implementation of the abstract class AbstractGetObservationHandler
 *
 * @since 4.0.0
 */
public class GetObservationDAO extends AbstractGetObservationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GetObservationDAO.class);

    private HibernateSessionHolder sessionHolder;
    private FeatureQueryHandler featureQueryHandler;
    private ServiceMetadataRepository serviceMetadataRepository;

    public GetObservationDAO() {
        super(SosConstants.SOS);
    }

    @Inject
    public void setServiceMetadataRepository(ServiceMetadataRepository repo) {
        this.serviceMetadataRepository = repo;
    }

    @Inject
    public void setConnectionProvider(ConnectionProvider connectionProvider) {
        this.sessionHolder = new HibernateSessionHolder(connectionProvider);
    }

    @Inject
    public void setFeatureQueryHandler(FeatureQueryHandler featureQueryHandler) {
        this.featureQueryHandler = featureQueryHandler;
    }

    @Override
    public GetObservationResponse getObservation(final GetObservationRequest sosRequest) throws OwsExceptionReport {
        if (sosRequest.getVersion().equals(Sos1Constants.SERVICEVERSION)
                && sosRequest.getObservedProperties().isEmpty()) {
            throw new MissingObservedPropertyParameterException();
        }
        if (sosRequest.isSetResultFilter()) {
            throw new NotYetSupportedException("result filtering");
        }
        final GetObservationResponse sosResponse = new GetObservationResponse();
        sosResponse.setService(sosRequest.getService());
        sosResponse.setVersion(sosRequest.getVersion());
        sosResponse.setResponseFormat(sosRequest.getResponseFormat());
        if (sosRequest.isSetResultModel()) {
            sosResponse.setResultModel(sosRequest.getResultModel());
        }
        Session session = null;
        try {
            session = sessionHolder.getSession();
            if (HibernateStreamingConfiguration.getInstance().isForceDatasourceStreaming()
                    && CollectionHelper.isEmpty(sosRequest.getFirstLatestTemporalFilter())) {
                // TODO
                if (EntitiyHelper.getInstance().isSeriesSupported()) {
                    sosResponse.setObservationCollection(querySeriesObservationForStreaming(sosRequest, session));
                } else {
                    sosResponse.setObservationCollection(queryObservationForStreaming(sosRequest, session));
                }
            } else {
                AbstractObservationDAO observationDAO = DaoFactory.getInstance().getObservationDAO();
                // check if series mapping is supported
                if (observationDAO instanceof AbstractSeriesObservationDAO) {
                    sosResponse.setObservationCollection(querySeriesObservation(sosRequest, (AbstractSeriesObservationDAO)observationDAO, session));
                } else {
                    // if
                    // (getConfigurator().getProfileHandler().getActiveProfile().isShowMetadataOfEmptyObservations())
                    // {
                    // // TODO Hydro-Profile adds result observation metadata
                    // // to
                    // // response
                    // sosResponse.setObservationCollection(queryObservationHydro(sosRequest,
                    // session));
                    // } else {
                    sosResponse.setObservationCollection(queryObservation(sosRequest, (LegacyObservationDAO)observationDAO, session));
                    // }
                }

            }
        } catch (HibernateException he) {
            throw new NoApplicableCodeException().causedBy(he).withMessage("Error while querying observation data!")
                    .setStatus(HTTPStatus.INTERNAL_SERVER_ERROR);
        } catch (ConverterException ce) {
            throw new NoApplicableCodeException().causedBy(ce).withMessage("Error while processing observation data!")
                    .setStatus(HTTPStatus.INTERNAL_SERVER_ERROR);
        } finally {
            sessionHolder.returnSession(session);
        }
        return sosResponse;
    }

    @Override
    public Set<String> getConformanceClasses(String service, String version) {
        if(SosConstants.SOS.equals(service) && Sos2Constants.SERVICEVERSION.equals(version)) {
            if (ServiceConfiguration.getInstance().isStrictSpatialFilteringProfile()) {
                return Sets.newHashSet(ConformanceClasses.SOS_V2_SPATIAL_FILTERING_PROFILE);
            }
        }
        return super.getConformanceClasses(service, version);
    }

    /**
     * Query observations from database depending on requested filters
     *
     * @param request
     *            GetObservation request
     * @param observationDAO
     * @param session
     *            Hibernate session
     * @return List of internal Observation objects
     * @throws OwsExceptionReport
     *             If an error occurs during requesting
     * @throws ConverterException
     *             If an error occurs during converting
     */
    // TODO move this and associated methods to ObservationDAO
    protected List<OmObservation> queryObservation(final GetObservationRequest request, LegacyObservationDAO observationDAO, final Session session)
            throws OwsExceptionReport, ConverterException {
        if (request.isSetResultFilter()) {
            throw new NotYetSupportedException("result filtering");
        }

        final long start = System.currentTimeMillis();
        // get valid featureOfInterest identifier
        final Set<String> features = getFeatures(request, session);
        if (features != null && features.isEmpty()) {
            return new ArrayList<>();
        }
        // temporal filters
        final List<IndeterminateValue> sosIndeterminateTimeFilters = request.getFirstLatestTemporalFilter();
        final Criterion filterCriterion = HibernateGetObservationHelper.getTemporalFilterCriterion(request);

        // final List<OmObservation> result = new LinkedList<OmObservation>();
        Collection<Observation<?>> observations = Lists.newArrayList();
        // query with temporal filter
        if (filterCriterion != null) {
            observations = observationDAO.getObservationsFor(request, features, filterCriterion, session);
        }
        // query with first/latest value filter
        else if (CollectionHelper.isNotEmpty(sosIndeterminateTimeFilters)) {
            for (IndeterminateValue sosIndeterminateTime : sosIndeterminateTimeFilters) {
                if (ServiceConfiguration.getInstance().isOverallExtrema()) {
                    observations = observationDAO.getObservationsFor(request, features, sosIndeterminateTime, session);
                } else {
                    for (ObservationConstellation oc : HibernateGetObservationHelper
                            .getAndCheckObservationConstellationSize(request, session)) {
                        for (String feature : HibernateGetObservationHelper.getAndCheckFeatureOfInterest(oc, features,
                                session)) {
                            observations.addAll(observationDAO.getObservationsFor(oc, Sets.newHashSet(feature),
                                    request, sosIndeterminateTime, session));
                        }
                    }
                }
            }
        }
        // query without temporal or indeterminate filters
        else {
            observations = observationDAO.getObservationsFor(request, features, session);
        }

        int metadataObservationsCount = 0;

        List<OmObservation> result = HibernateGetObservationHelper.toSosObservation(observations, request, this.serviceMetadataRepository.getServiceProviderFactory(request.getService()), LocaleHelper.fromString(request.getRequestedLanguage()), session);
        Set<OmObservationConstellation> timeSeries = Sets.newHashSet();
        if (ProfileHandler.getInstance().getActiveProfile().isShowMetadataOfEmptyObservations()
                || ServiceConfiguration.getInstance().getMaxNumberOfReturnedTimeSeries() > 0) {
            result.stream().map(OmObservation::getObservationConstellation).forEach(timeSeries::add);
        }
        if (ProfileHandler.getInstance().getActiveProfile().isShowMetadataOfEmptyObservations()) {
            // create a map of series to check by id, so we don't need to fetch
            // each observation's series from the database
            for (ObservationConstellation oc : HibernateGetObservationHelper.getAndCheckObservationConstellationSize(
                    request, session)) {
                final List<String> featureIds =
                        HibernateGetObservationHelper.getAndCheckFeatureOfInterest(oc, features, session);
                for (OmObservation omObservation : HibernateObservationUtilities.createSosObservationFromObservationConstellation(oc,
                        featureIds, request, this.serviceMetadataRepository.getServiceProviderFactory(request.getService()), LocaleHelper.fromString(request.getRequestedLanguage()), session)) {
                    if (!timeSeries.contains(omObservation.getObservationConstellation())) {
                        result.add(omObservation);
                        timeSeries.add(omObservation.getObservationConstellation());
                    }
                }
            }
        }

        HibernateGetObservationHelper
                .checkMaxNumberOfReturnedSeriesSize(timeSeries.size() + metadataObservationsCount);
        HibernateGetObservationHelper.checkMaxNumberOfReturnedValues(observations.size());
        LOGGER.debug("Time to query observations needed {} ms!", (System.currentTimeMillis() - start));
        return result;
    }

    /**
     * Query observation if the series mapping is supported.
     *
     * @param request
     *            GetObservation request
     * @param observationDAO
     * @param session
     *            Hibernate session
     * @return List of internal Observations
     * @throws OwsExceptionReport
     *             If an error occurs.
     * @throws ConverterException
     *             If an error occurs during sensor description creation.
     */
    protected List<OmObservation> querySeriesObservation(GetObservationRequest request, AbstractSeriesObservationDAO observationDAO, Session session)
            throws OwsExceptionReport, ConverterException {
        if (request.isSetResultFilter()) {
            throw new NotYetSupportedException("result filtering");
        }

        final long start = System.currentTimeMillis();
        // get valid featureOfInterest identifier
        final Set<String> features = getFeatures(request, session);
        if (features != null && features.isEmpty()) {
            return new LinkedList<>();
        }
        // temporal filters
        final List<IndeterminateValue> sosIndeterminateTimeFilters = request.getFirstLatestTemporalFilter();
        final Criterion filterCriterion = HibernateGetObservationHelper.getTemporalFilterCriterion(request);

        final List<OmObservation> result = new LinkedList<>();
        Collection<SeriesObservation<?>> seriesObservations = Lists.newArrayList();

        AbstractSeriesDAO seriesDAO = DaoFactory.getInstance().getSeriesDAO();

        // query with temporal filter
        if (filterCriterion != null) {
            seriesObservations =
                    observationDAO.getSeriesObservationsFor(request, features, filterCriterion, session);
        }
        // query with first/latest value filter
        else if (CollectionHelper.isNotEmpty(sosIndeterminateTimeFilters)) {
            for (IndeterminateValue sosIndeterminateTime : sosIndeterminateTimeFilters) {
                if (ServiceConfiguration.getInstance().isOverallExtrema()) {
                    seriesObservations =
                            observationDAO.getSeriesObservationsFor(request, features,
                                    sosIndeterminateTime, session);
                } else {
                    for (Series series : seriesDAO.getSeries(request, features, session)) {
                        seriesObservations.addAll(observationDAO.getSeriesObservationsFor(series, request,
                                sosIndeterminateTime, session));
                    }
                }
            }
        }
        // query without temporal or indeterminate filters
        else {
            seriesObservations = observationDAO.getSeriesObservationsFor(request, features, session);
        }

        // if active profile demands observation metadata for series without
        // matching observations,
        // a "result" observation without values is created.
        // TODO does this apply for indeterminate time first/latest filters?
        // Yes.
        int metadataObservationsCount = 0;
        if (ProfileHandler.getInstance().getActiveProfile().isShowMetadataOfEmptyObservations()) {
            // create a map of series to check by id, so we don't need to fetch
            // each observation's series from the database
            Map<Long, Series> seriesToCheckMap = Maps.newHashMap();
            for (Series series : seriesDAO.getSeries(request, features, session)) {
                seriesToCheckMap.put(series.getSeriesId(), series);
            }

            // check observations and remove any series found from the map
            for (SeriesObservation<?> seriesObs : seriesObservations) {
                long seriesId = seriesObs.getSeries().getSeriesId();
                if (seriesToCheckMap.containsKey(seriesId)) {
                    seriesToCheckMap.remove(seriesId);
                }
            }
            // now we're left with the series without matching observations in
            // the check map,
            // add "result" observations for them
            metadataObservationsCount = seriesToCheckMap.size();
            for (Series series : seriesToCheckMap.values()) {
                result.addAll(HibernateObservationUtilities.createSosObservationFromSeries(series,
                        request, this.serviceMetadataRepository.getServiceProviderFactory(request.getService()), LocaleHelper.fromString(request.getRequestedLanguage()), session));
            }
        }
        HibernateGetObservationHelper
                .checkMaxNumberOfReturnedTimeSeries(seriesObservations, metadataObservationsCount);
        HibernateGetObservationHelper.checkMaxNumberOfReturnedValues(seriesObservations.size());

        LOGGER.debug("Time to query observations needs {} ms!", (System.currentTimeMillis() - start));
        Collection<Observation<?>> abstractObservations = Lists.newArrayList();
        abstractObservations.addAll(seriesObservations);
        result.addAll(HibernateGetObservationHelper.toSosObservation(abstractObservations, request, this.serviceMetadataRepository.getServiceProviderFactory(request.getService()), LocaleHelper.fromString(request.getRequestedLanguage()), session));
        return result;
    }

    /**
     * Query the observations for streaming datasource
     *
     * @param request
     *            The GetObservation request
     * @param session
     *            Hibernate Session
     * @return List of internal observations
     * @throws OwsExceptionReport
     *             If an error occurs.
     * @throws ConverterException
     *             If an error occurs during sensor description creation.
     */
    protected List<OmObservation> queryObservationForStreaming(GetObservationRequest request, final Session session)
            throws OwsExceptionReport, ConverterException {
        final long start = System.currentTimeMillis();
        final List<OmObservation> result = new LinkedList<>();
        // get valid featureOfInterest identifier
        final Set<String> features = getFeatures(request, session);
        if (features != null && features.isEmpty()) {
            return result;
        }
        Criterion temporalFilterCriterion = HibernateGetObservationHelper.getTemporalFilterCriterion(request);
        List<ObservationConstellation> observations = HibernateGetObservationHelper.getAndCheckObservationConstellationSize(
                request, session);
        HibernateGetObservationHelper.checkMaxNumberOfReturnedSeriesSize(observations.size());
        int maxNumberOfValuesPerSeries = HibernateGetObservationHelper.getMaxNumberOfValuesPerSeries(observations.size());
        for (ObservationConstellation oc : observations) {
            final List<String> featureIds =
                    HibernateGetObservationHelper.getAndCheckFeatureOfInterest(oc, features, session);
            for (OmObservation observationTemplate : HibernateObservationUtilities
                    .createSosObservationFromObservationConstellation(oc, featureIds, request,this.serviceMetadataRepository.getServiceProviderFactory(request.getService()), LocaleHelper.fromString(request.getRequestedLanguage()), session)) {
                FeatureOfInterest featureOfInterest =
                        new FeatureOfInterestDAO().getFeatureOfInterest(observationTemplate
                                .getObservationConstellation().getFeatureOfInterest().getIdentifier(),
                                session);
                HibernateStreamingValue streamingValue =
                        getStreamingValue(request, oc.getProcedure().getProcedureId(), oc.getObservableProperty()
                                .getObservablePropertyId(), featureOfInterest.getFeatureOfInterestId());
                streamingValue.setResponseFormat(request.getResponseFormat());
                streamingValue.setTemporalFilterCriterion(temporalFilterCriterion);
                streamingValue.setObservationTemplate(observationTemplate);
                streamingValue.setMaxNumberOfValues(maxNumberOfValuesPerSeries);
                observationTemplate.setValue(streamingValue);
                result.add(observationTemplate);
            }
        }
        LOGGER.debug("Time to query observations needs {} ms!", (System.currentTimeMillis() - start));
        return result;
    }

    /**
     * Query the series observations for streaming datasource
     *
     * @param request
     *            The GetObservation request
     * @param session
     *            Hibernate Session
     * @return List of internal observations
     * @throws OwsExceptionReport
     *             If an error occurs.
     * @throws ConverterException
     *             If an error occurs during sensor description creation.
     */
    protected List<OmObservation> querySeriesObservationForStreaming(GetObservationRequest request,
            final Session session) throws OwsExceptionReport, ConverterException {
        final long start = System.currentTimeMillis();
        final List<OmObservation> result = new LinkedList<>();
        // get valid featureOfInterest identifier
        final Set<String> features = getFeatures(request, session);
        if (features != null && features.isEmpty()) {
            return result;
        }
        Criterion temporalFilterCriterion = HibernateGetObservationHelper.getTemporalFilterCriterion(request);
        List<Series> serieses = DaoFactory.getInstance().getSeriesDAO().getSeries(request, features, session);
        HibernateGetObservationHelper.checkMaxNumberOfReturnedSeriesSize(serieses.size());
        int maxNumberOfValuesPerSeries = HibernateGetObservationHelper.getMaxNumberOfValuesPerSeries(serieses.size());
        for (Series series : serieses) {
            Locale locale = LocaleHelper.fromString(request.getRequestedLanguage());
            String service = request.getService();
            LocalizedProducer<OwsServiceProvider> serviceProviderFactory
                    = this.serviceMetadataRepository.getServiceProviderFactory(service);
            Collection<? extends OmObservation> createSosObservationFromSeries =
                    HibernateObservationUtilities
                            .createSosObservationFromSeries(series, request, serviceProviderFactory, locale, session);
            OmObservation observationTemplate = createSosObservationFromSeries.iterator().next();
            HibernateSeriesStreamingValue streamingValue = getSeriesStreamingValue(request, series.getSeriesId());
            streamingValue.setResponseFormat(request.getResponseFormat());
            streamingValue.setTemporalFilterCriterion(temporalFilterCriterion);
            streamingValue.setObservationTemplate(observationTemplate);
            streamingValue.setMaxNumberOfValues(maxNumberOfValuesPerSeries);
            observationTemplate.setValue(streamingValue);
            result.add(observationTemplate);
        }
        LOGGER.debug("Time to query observations needs {} ms!", (System.currentTimeMillis() - start));
        return result;
    }

    private Set<String> getFeatures(GetObservationRequest request, Session session) throws OwsExceptionReport {
        return QueryHelper.getFeatures(this.featureQueryHandler, request, session);
    }

    /**
     * Get the series streaming observation value for the observations
     *
     * @param request
     *            GetObservation request
     * @param seriesId
     *            Series id
     * @return Streaming observation value
     * @throws CodedException
     */
    private HibernateSeriesStreamingValue getSeriesStreamingValue(GetObservationRequest request, long seriesId) throws CodedException {
        if (HibernateStreamingConfiguration.getInstance().isChunkDatasourceStreaming()) {
            return new HibernateChunkSeriesStreamingValue(getConnectionProvider(), request, seriesId);
        } else {
            return new HibernateScrollableSeriesStreamingValue(getConnectionProvider(), request, seriesId);
        }
    }

    /**
     * Get the streaming observation value for the observations
     *
     * @param request
     *            GetObservation request
     * @param procedure
     *            Procedure id
     * @param observableProperty
     *            ObservableProperty id
     * @param feature
     *            FeatureOfInterest id
     * @return Streaming observation value
     */
    private HibernateStreamingValue getStreamingValue(GetObservationRequest request, long procedure,
            long observableProperty, long feature) {
        if (HibernateStreamingConfiguration.getInstance().isChunkDatasourceStreaming()) {
            return new HibernateChunkStreamingValue(getConnectionProvider(), request, procedure, observableProperty, feature);
        } else {
            return new HibernateScrollableStreamingValue(getConnectionProvider(), request, procedure, observableProperty, feature);
        }
    }

    protected ConnectionProvider getConnectionProvider() {
        return this.sessionHolder.getConnectionProvider();
    }

}
