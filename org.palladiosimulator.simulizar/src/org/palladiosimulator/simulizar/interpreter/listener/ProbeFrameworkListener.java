package org.palladiosimulator.simulizar.interpreter.listener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.unit.SI;

import org.apache.commons.collections15.CollectionUtils;
import org.apache.commons.collections15.PredicateUtils;
import org.apache.commons.collections15.Transformer;
import org.apache.log4j.Logger;
import org.eclipse.emf.ecore.EObject;
import org.palladiosimulator.edp2.models.measuringpoint.MeasuringPoint;
import org.palladiosimulator.edp2.models.measuringpoint.MeasuringpointFactory;
import org.palladiosimulator.edp2.models.measuringpoint.StringMeasuringPoint;
import org.palladiosimulator.edp2.util.MetricDescriptionUtility;
import org.palladiosimulator.experimentanalysis.ISlidingWindowListener;
import org.palladiosimulator.experimentanalysis.KeepLastElementPriorToLowerBoundStrategy;
import org.palladiosimulator.experimentanalysis.SlidingWindow.ISlidingWindowMoveOnStrategy;
import org.palladiosimulator.experimentanalysis.SlidingWindowRecorder;
import org.palladiosimulator.experimentanalysis.SlidingWindowUtilizationAggregator;
import org.palladiosimulator.measurementframework.listener.IMeasurementSourceListener;
import org.palladiosimulator.metricspec.MetricDescription;
import org.palladiosimulator.metricspec.MetricSetDescription;
import org.palladiosimulator.metricspec.constants.MetricDescriptionConstants;
import org.palladiosimulator.probeframework.calculator.Calculator;
import org.palladiosimulator.probeframework.calculator.ICalculatorFactory;
import org.palladiosimulator.probeframework.calculator.RegisterCalculatorFactoryDecorator;
import org.palladiosimulator.probeframework.probes.EventProbeList;
import org.palladiosimulator.probeframework.probes.Probe;
import org.palladiosimulator.probeframework.probes.TriggeredProbe;
import org.palladiosimulator.recorderframework.IRecorder;
import org.palladiosimulator.recorderframework.config.AbstractRecorderConfiguration;
import org.palladiosimulator.recorderframework.utils.RecorderExtensionHelper;
import org.palladiosimulator.simulizar.access.IModelAccess;
import org.palladiosimulator.simulizar.metrics.aggregators.ResponseTimeAggregator;
import org.palladiosimulator.simulizar.metrics.aggregators.SimulationGovernedSlidingWindow;
import org.palladiosimulator.simulizar.metrics.powerconsumption.EnergyConsumptionPrmRecorder;
import org.palladiosimulator.simulizar.metrics.powerconsumption.PowerConsumptionPrmRecorder;
import org.palladiosimulator.simulizar.metrics.powerconsumption.SimulationTimeEnergyConsumptionCalculator;
import org.palladiosimulator.simulizar.metrics.powerconsumption.SimulationTimeEvaluationScope;
import org.palladiosimulator.simulizar.metrics.powerconsumption.SimulationTimePowerConsumptionCalculator;
import org.palladiosimulator.simulizar.monitorrepository.DelayedIntervall;
import org.palladiosimulator.simulizar.monitorrepository.Intervall;
import org.palladiosimulator.simulizar.monitorrepository.MeasurementSpecification;
import org.palladiosimulator.simulizar.monitorrepository.Monitor;
import org.palladiosimulator.simulizar.monitorrepository.MonitorRepository;
import org.palladiosimulator.simulizar.monitorrepository.MonitorrepositoryFactory;
import org.palladiosimulator.simulizar.monitorrepository.util.MonitorrepositorySwitch;
import org.palladiosimulator.simulizar.prm.PRMModel;
import org.palladiosimulator.simulizar.utils.MonitorRepositoryUtil;

import de.fzi.power.infrastructure.PowerProvidingEntity;
import de.fzi.power.interpreter.ConsumptionContext;
import de.fzi.power.interpreter.InterpreterUtils;
import de.fzi.power.interpreter.PowerModelRegistry;
import de.fzi.power.interpreter.PowerModelUpdaterSwitch;
import de.fzi.power.interpreter.calculators.ExtensibleCalculatorInstantiatorImpl;
import de.fzi.power.interpreter.calculators.energy.AbstractCumulativeEnergyCalculator;
import de.fzi.power.interpreter.calculators.energy.SimpsonRuleCumulativeEnergyCalculator;
import de.uka.ipd.sdq.pcm.core.entity.Entity;
import de.uka.ipd.sdq.pcm.seff.ExternalCallAction;
import de.uka.ipd.sdq.pcm.usagemodel.EntryLevelSystemCall;
import de.uka.ipd.sdq.pcm.usagemodel.UsageScenario;
import de.uka.ipd.sdq.simucomframework.SimuComConfig;
import de.uka.ipd.sdq.simucomframework.model.SimuComModel;
import de.uka.ipd.sdq.simucomframework.probes.TakeCurrentSimulationTimeProbe;
import de.uka.ipd.sdq.simucomframework.probes.TakeNumberOfResourceContainersProbe;
import de.uka.ipd.sdq.simulation.ISimulationListener;

/**
 * Class for listening to interpreter events in order to store collected data using the
 * ProbeFramework
 * 
 * @author Steffen Becker, Sebastian Lehrig, Florian Rosenthal
 */
public class ProbeFrameworkListener extends AbstractInterpreterListener {

    private static final Logger LOGGER = Logger.getLogger(ProbeFrameworkListener.class);
    private static final int START_PROBE_INDEX = 0;
    private static final int STOP_PROBE_INDEX = 1;
    private static final MetricSetDescription POWER_CONSUMPTION_TUPLE_METRIC_DESC =
            MetricDescriptionConstants.POWER_CONSUMPTION_TUPLE;
    private static final MetricSetDescription ENERGY_CONSUMPTION_TUPLE_METRIC_DESC =
            MetricDescriptionConstants.CUMULATIVE_ENERGY_CONSUMPTION_TUPLE;
    private static final MetricSetDescription UTILIZATION_TUPLE_METRIC_DESC = 
            MetricDescriptionConstants.UTILIZATION_OF_ACTIVE_RESOURCE_TUPLE;

    private final MonitorRepository monitorRepositoryModel;
    private final PRMModel prmModel;
    private final SimuComModel simuComModel;
    private final ICalculatorFactory calculatorFactory;

    private final Map<String, List<TriggeredProbe>> currentTimeProbes = new HashMap<String, List<TriggeredProbe>>();
    private TriggeredProbe reconfTimeProbe;

    /** Default EMF factory for measuring points. */
    private final MeasuringpointFactory measuringpointFactory = MeasuringpointFactory.eINSTANCE;

    /**
     * @param modelAccessFactory
     *            Provides access to simulated models
     * @param simuComModel
     *            Provides access to the central simulation
     */
    public ProbeFrameworkListener(IModelAccess modelAccessFactory, SimuComModel simuComModel) {
        super();
        this.monitorRepositoryModel = modelAccessFactory.getMonitorRepositoryModel();
        this.prmModel = modelAccessFactory.getPRMModel();
        this.calculatorFactory = simuComModel.getProbeFrameworkContext().getCalculatorFactory();
        this.simuComModel = simuComModel;
        this.reconfTimeProbe = null;

        initReponseTimeMeasurement();
        initNumberOfResourceContainersMeasurements();
        initUtilizationMeasurements();
        initPowerMeasurements();
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.upb.pcm.interpreter.interpreter.listener.AbstractInterpreterListener#
     * beginUsageScenarioInterpretation
     * (de.upb.pcm.interpreter.interpreter.listener.ModelElementPassedEvent)
     */
    @Override
    public void beginUsageScenarioInterpretation(final ModelElementPassedEvent<UsageScenario> event) {
        this.startMeasurement(event);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.upb.pcm.interpreter.interpreter.listener.AbstractInterpreterListener#
     * endUsageScenarioInterpretation
     * (de.upb.pcm.interpreter.interpreter.listener.ModelElementPassedEvent)
     */
    @Override
    public void endUsageScenarioInterpretation(final ModelElementPassedEvent<UsageScenario> event) {
        this.endMeasurement(event);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.upb.pcm.interpreter.interpreter.listener.AbstractInterpreterListener#
     * beginEntryLevelSystemCallInterpretation
     * (de.upb.pcm.interpreter.interpreter.listener.ModelElementPassedEvent)
     */
    @Override
    public void beginEntryLevelSystemCallInterpretation(final ModelElementPassedEvent<EntryLevelSystemCall> event) {
        this.startMeasurement(event);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.upb.pcm.interpreter.interpreter.listener.AbstractInterpreterListener#
     * endEntryLevelSystemCallInterpretation
     * (de.upb.pcm.interpreter.interpreter.listener.ModelElementPassedEvent)
     */
    @Override
    public void endEntryLevelSystemCallInterpretation(final ModelElementPassedEvent<EntryLevelSystemCall> event) {
        this.endMeasurement(event);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.upb.pcm.simulizar.interpreter.listener.AbstractInterpreterListener#
     * beginExternalCallInterpretation
     * (de.upb.pcm.simulizar.interpreter.listener.ModelElementPassedEvent)
     */
    @Override
    public void beginExternalCallInterpretation(final RDSEFFElementPassedEvent<ExternalCallAction> event) {
        this.startMeasurement(event);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.upb.pcm.simulizar.interpreter.listener.AbstractInterpreterListener#
     * endExternalCallInterpretation
     * (de.upb.pcm.simulizar.interpreter.listener.ModelElementPassedEvent)
     */
    @Override
    public void endExternalCallInterpretation(final RDSEFFElementPassedEvent<ExternalCallAction> event) {
        this.endMeasurement(event);
    }

    @Override
    public <T extends EObject> void beginUnknownElementInterpretation(ModelElementPassedEvent<T> event) {
    }

    @Override
    public <T extends EObject> void endUnknownElementInterpretation(ModelElementPassedEvent<T> event) {
    }

    /**
     * Gets all {@link MeasurementSpecification}s within the current {@code monitorRepositoryModel}
     * that adhere to the given metric.
     * @param soughtFor A {@link MetricDescription} denoting the target metric to look for.
     * @return An UNMIDIFIABLE {@link Collection} containing all found measurement Specifications, which might be empty but never {@code null}.
     */
    private Collection<MeasurementSpecification> getMeasurementSpecificationsForMetricDescription(
            final MetricDescription soughtFor) {
        assert soughtFor != null;
        if (this.monitorRepositoryModel != null) {
            Transformer<Monitor, MeasurementSpecification> transformer =
                    new Transformer<Monitor, MeasurementSpecification>() {

                        @Override
                        public MeasurementSpecification transform(Monitor monitor) {
                            for (MeasurementSpecification m : monitor.getMeasurementSpecifications()) {
                                if (MetricDescriptionUtility.metricDescriptionIdsEqual(m.getMetricDescription(), soughtFor)) {
                                    return m;
                                }
                            }
                            return null;
                        }
                    };
            return Collections.unmodifiableCollection(CollectionUtils.select(
                    CollectionUtils.collect(this.monitorRepositoryModel.getMonitors(), transformer),
                    PredicateUtils.notNullPredicate()));
        }
        return Collections.emptyList();
    }
    
    /**
     * returns a two-element array: sliding window length is returned at index 0, window increment
     * at index 1
     */
    private static final MonitorrepositorySwitch<Measure<Double, Duration>[]> WINDOW_PROPERTIES_SWITCH =
            new MonitorrepositorySwitch<Measure<Double, Duration>[]>() {

        @Override
        public Measure<Double, Duration>[] caseDelayedIntervall(DelayedIntervall interval) {
            @SuppressWarnings("unchecked")
            Measure<Double, Duration>[] result = (Measure<Double, Duration>[]) new Measure<?, ?>[2];
            result[0] = Measure.valueOf(interval.getIntervall(), SI.SECOND);
            result[1] = Measure.valueOf(interval.getDelay(), SI.SECOND);
            return result;
        }

        @Override
        public Measure<Double, Duration>[] caseIntervall(Intervall interval) {
            @SuppressWarnings("unchecked")
            Measure<Double, Duration>[] result = (Measure<Double, Duration>[]) new Measure<?, ?>[2];
            result[0] = Measure.valueOf(interval.getIntervall(), SI.SECOND);
            result[1] = result[0];

            return result;
        }

        @Override
        public Measure<Double, Duration>[] defaultCase(EObject obj) {
            throw new IllegalStateException(
                    "Temporal characterization for utilization or measurement must be either Intervall or DelayedIntervall.");
        }
    };

    /**
     * Convenience method to created a recorder configuration map which has the
     * {@link AbstractRecorderConfiguration#RECORDER_ACCEPTED_METRIC} attribute (key) set to the given
     * metric description.
     * @param recorderAcceptedMetric The {@link MetricDescription} to be put in the map.
     * @return A recorder configuration {@link Map} initialized as described.
     */
    private static Map<String, Object> createRecorderConfigMapWithAcceptedMetric(
            MetricDescription recorderAcceptedMetric) {
        assert recorderAcceptedMetric != null;

        Map<String, Object> result = new HashMap<>();
        result.put(AbstractRecorderConfiguration.RECORDER_ACCEPTED_METRIC, recorderAcceptedMetric);
        return result;
    }

    /**
     * Instantiates and initializes a {@link IRecorder} implementation based on the {@link SimuComConfig} of the current
     * SimuLizar run.
     * @param recorderConfigMap A {@link Map} which contains the recorder configuration attributes.
     * @return An {@link IRecorder} initialized with the given configuration.
     */
    private IRecorder initializeRecorder(Map<String, Object> recorderConfigMap) {
        assert recorderConfigMap != null;

        SimuComConfig config = this.simuComModel.getConfiguration();
        IRecorder recorder = RecorderExtensionHelper.instantiateRecorderImplementationForRecorder(config
                .getRecorderName());
        recorder.initialize(config.getRecorderConfigurationFactory().createRecorderConfiguration(recorderConfigMap));

        return recorder;
    }

   
   private void initPowerMeasurements() {
        Collection<MeasurementSpecification> powerMeasurementSpecs =
               getMeasurementSpecificationsForMetricDescription(POWER_CONSUMPTION_TUPLE_METRIC_DESC);
        if (!powerMeasurementSpecs.isEmpty()) {
            Map<String, Object> powerRecorderConfigurationMap =
                    createRecorderConfigMapWithAcceptedMetric(POWER_CONSUMPTION_TUPLE_METRIC_DESC);
            Map<String, Object> energyRecorderConfigurationMap =
                    createRecorderConfigMapWithAcceptedMetric(ENERGY_CONSUMPTION_TUPLE_METRIC_DESC);
            PowerModelRegistry reg = new PowerModelRegistry();
            PowerModelUpdaterSwitch modelUpdaterSwitch = new PowerModelUpdaterSwitch(reg,
                    new ExtensibleCalculatorInstantiatorImpl());
            List<ConsumptionContext> createdContexts = new ArrayList<>(powerMeasurementSpecs.size());
            List<SimulationTimeEvaluationScope> createdScopes = new ArrayList<>(powerMeasurementSpecs.size());
            
            for (MeasurementSpecification powerSpec : powerMeasurementSpecs) {
                MeasuringPoint mp = powerSpec.getMonitor().getMeasuringPoint();
                PowerProvidingEntity ppe = InterpreterUtils.getPowerProvindingEntityFromMeasuringPoint(mp);
                if (ppe == null) {
                    throw new IllegalStateException("MeasurementSpecification for metric " 
                            + POWER_CONSUMPTION_TUPLE_METRIC_DESC.getName() + " has to be related to a PowerProvidingEntity!");
                }

                energyRecorderConfigurationMap.put(AbstractRecorderConfiguration.MEASURING_POINT, mp);
                powerRecorderConfigurationMap.put(AbstractRecorderConfiguration.MEASURING_POINT, mp);

                Measure<Double, Duration>[] windowProperties = WINDOW_PROPERTIES_SWITCH.doSwitch(powerSpec
                        .getTemporalRestriction());
                Measure<Double, Duration> initialOffset = windowProperties[0];
                Measure<Double, Duration> samplingPeriod = windowProperties[1];
                SimulationTimeEvaluationScope scope = SimulationTimeEvaluationScope.createScope(ppe,
                        this.simuComModel, initialOffset, samplingPeriod);

                modelUpdaterSwitch.doSwitch(ppe);
                ConsumptionContext context = ConsumptionContext.createConsumptionContext(ppe, scope, reg);

                AbstractCumulativeEnergyCalculator energyCalculator =
                        new SimpsonRuleCumulativeEnergyCalculator(samplingPeriod, initialOffset);

                createdContexts.add(context);
                createdScopes.add(scope);
                SimulationTimePowerConsumptionCalculator powerConsumptionCalculator =
                        new SimulationTimePowerConsumptionCalculator(context, scope, ppe);
                SimulationTimeEnergyConsumptionCalculator energyConsumptionCalculator =
                        new SimulationTimeEnergyConsumptionCalculator(energyCalculator);
                
                scope.addListener(powerConsumptionCalculator);
                powerConsumptionCalculator.addObserver(initializeRecorder(powerRecorderConfigurationMap));
                powerConsumptionCalculator.addObserver(new PowerConsumptionPrmRecorder(this.prmModel, powerSpec, ppe));
                powerConsumptionCalculator.addObserver(energyConsumptionCalculator);
                energyConsumptionCalculator.addObserver(initializeRecorder(energyRecorderConfigurationMap));
                MeasurementSpecification energySpec = MonitorrepositoryFactory.eINSTANCE.createMeasurementSpecification();
                energySpec.setMetricDescription(ENERGY_CONSUMPTION_TUPLE_METRIC_DESC);
                energySpec.setMonitor(powerSpec.getMonitor());
                powerSpec.getMonitor().getMeasurementSpecifications().add(energySpec);
                energySpec.setTemporalRestriction(powerSpec.getTemporalRestriction());
                energyConsumptionCalculator.addObserver(new EnergyConsumptionPrmRecorder(this.prmModel, energySpec, ppe));
            }
            triggerAfterSimulationCleanup(createdContexts, createdScopes);
        }
    }

   /**
    * Method to clean up {@link ConsumptionContext}s and {@link SimulationTimeEvaluationScope}s required
    * for power and energy measurements. The clean-up operations are done once the simulation has stopped.
    * @param contextsToCleanup {@link Collection} of contexts to clean up.
    * @param scopesToCleanup {@link Collection} of scopes to clean up.
    */
    private void triggerAfterSimulationCleanup(final Collection<ConsumptionContext> contextsToCleanup,
            final Collection<SimulationTimeEvaluationScope> scopesToCleanup) {
        assert contextsToCleanup != null && !contextsToCleanup.isEmpty();
        assert scopesToCleanup != null && !scopesToCleanup.isEmpty();

        this.simuComModel.getConfiguration().addListener(new ISimulationListener() {
            @Override
            public void simulationStop() {
                for (ConsumptionContext context : contextsToCleanup) {
                    context.cleanUp();
                }
                for (SimulationTimeEvaluationScope scope : scopesToCleanup) {
                    scope.removeAllListeners();
                }
            }
            @Override
            public void simulationStart() {
            }
        });
    }

    private void initUtilizationMeasurements() {
        Collection<MeasurementSpecification> utilMeasurementSpecs =
                getMeasurementSpecificationsForMetricDescription(UTILIZATION_TUPLE_METRIC_DESC);
        if (!utilMeasurementSpecs.isEmpty()) {
            RegisterCalculatorFactoryDecorator calcFactory = RegisterCalculatorFactoryDecorator.class
                    .cast(this.calculatorFactory);
            ISlidingWindowMoveOnStrategy strategy = new KeepLastElementPriorToLowerBoundStrategy();

            for (MeasurementSpecification spec : utilMeasurementSpecs) {
                MeasuringPoint mp = spec.getMonitor().getMeasuringPoint();

                Calculator calculator = calcFactory.getCalculatorByMeasuringPointAndMetricDescription(mp,
                        MetricDescriptionConstants.STATE_OF_ACTIVE_RESOURCE_METRIC_TUPLE);
                if (calculator == null) {
                    throw new IllegalStateException(
                            "Utilization measurements (sliding window based) cannot be initialized.\n"
                                    + "No state of active resource calculator available for: "
                                    + mp.getStringRepresentation() + "\n"
                                    + "Ensure that initializeModelSyncers() in SimulizarRuntimeState is called prior "
                                    + "to initializeInterpreterListeners()!");
                }
                setupUtilizationRecorder(calculator, spec, strategy);
            }
        }
    }

    private Calculator setupUtilizationRecorder(Calculator calculator,
            MeasurementSpecification utilizationMeasurementSpec, ISlidingWindowMoveOnStrategy moveOnStrategy) {

        Measure<Double, Duration>[] windowProperties = WINDOW_PROPERTIES_SWITCH.doSwitch(utilizationMeasurementSpec
                .getTemporalRestriction());

        Map<String, Object> recorderConfigurationMap =
                createRecorderConfigMapWithAcceptedMetric(UTILIZATION_TUPLE_METRIC_DESC);
        recorderConfigurationMap.put(AbstractRecorderConfiguration.MEASURING_POINT, calculator.getMeasuringPoint());
        
        IRecorder baseRecorder = initializeRecorder(recorderConfigurationMap);

        SimulationGovernedSlidingWindow window = new SimulationGovernedSlidingWindow(windowProperties[0],
                windowProperties[1], MetricDescriptionConstants.STATE_OF_ACTIVE_RESOURCE_METRIC_TUPLE, moveOnStrategy,
                this.simuComModel);

        ISlidingWindowListener aggregator = new SlidingWindowUtilizationAggregator(baseRecorder);
        SlidingWindowRecorder windowRecorder = new SlidingWindowRecorder(window, aggregator);
        // register recorder at calculator
        calculator.addObserver(windowRecorder);
        return calculator;
    }

    /**
     * Initialize the response time measurements. First get the monitored elements from the monitor repository,
     * then create corresponding calculators and aggregators.
     * 
     */
    private void initReponseTimeMeasurement() {
        for (MeasurementSpecification responseTimeMeasurementSpec :
                getMeasurementSpecificationsForMetricDescription(MetricDescriptionConstants.RESPONSE_TIME_METRIC)) {
            MeasuringPoint measuringPoint = responseTimeMeasurementSpec.getMonitor().getMeasuringPoint();
            EObject modelElement = MonitorRepositoryUtil.getMonitoredElement(measuringPoint);

            List<Probe> probeList = createStartAndStopProbe(modelElement, this.simuComModel);
            Calculator calculator = this.calculatorFactory.buildResponseTimeCalculator(measuringPoint, probeList);

            try {
                IMeasurementSourceListener aggregator = new ResponseTimeAggregator(this.simuComModel, this.prmModel,
                        responseTimeMeasurementSpec, modelElement);
                calculator.addObserver(aggregator);
            } catch (final UnsupportedOperationException e) {
                LOGGER.error(e);
                throw new RuntimeException(e);
            }
        }
    }

    private void initNumberOfResourceContainersMeasurements() {
        for (MeasurementSpecification numberOfResourceContainersMeasurementSpec :
            getMeasurementSpecificationsForMetricDescription(MetricDescriptionConstants.NUMBER_OF_RESOURCE_CONTAINERS)) {
            MeasuringPoint measuringPoint = numberOfResourceContainersMeasurementSpec.getMonitor().getMeasuringPoint();

            final Probe probe = new EventProbeList(new TakeNumberOfResourceContainersProbe(
                    simuComModel.getResourceRegistry()),
            Arrays.asList((TriggeredProbe) new TakeCurrentSimulationTimeProbe(simuComModel
                        .getSimulationControl())));
            calculatorFactory.buildNumberOfResourceContainersCalculator(measuringPoint, probe);
        }
    }
    
        /**
         * @param modelElement
         * @param simuComModel
         * @return list with start and stop probe
         */
        @SuppressWarnings({ "rawtypes", "unchecked" })
        protected List<Probe> createStartAndStopProbe(final EObject modelElement, final SimuComModel simuComModel) {
            final List probeList = new ArrayList<TriggeredProbe>(2);
            probeList.add(new TakeCurrentSimulationTimeProbe(simuComModel.getSimulationControl()));
            probeList.add(new TakeCurrentSimulationTimeProbe(simuComModel.getSimulationControl()));
            currentTimeProbes.put(((Entity) modelElement).getId(), Collections.unmodifiableList(probeList));
            return probeList;
        }

    /**
     * @param modelElement
     * @return
     */
    protected boolean entityIsAlreadyInstrumented(final EObject modelElement) {
        return this.currentTimeProbes.containsKey(((Entity) modelElement).getId());
    }

    /**
     * @param <T>
     * @param event
     */
    private <T extends Entity> void startMeasurement(final ModelElementPassedEvent<T> event) {
        if (this.currentTimeProbes.containsKey(((Entity) event.getModelElement()).getId()) && simulationIsRunning()) {
            this.currentTimeProbes.get(((Entity) event.getModelElement()).getId()).get(START_PROBE_INDEX)
                    .takeMeasurement(event.getThread().getRequestContext());
        }
    }

    /**
     * @param event
     */
    private <T extends Entity> void endMeasurement(final ModelElementPassedEvent<T> event) {
        if (this.currentTimeProbes.containsKey(((Entity) event.getModelElement()).getId()) && simulationIsRunning()) {
            this.currentTimeProbes.get(((Entity) event.getModelElement()).getId()).get(STOP_PROBE_INDEX)
                    .takeMeasurement(event.getThread().getRequestContext());
        }
    }

    @Override
    public void reconfigurationInterpretation(final ReconfigurationEvent event) {
        if (this.reconfTimeProbe == null) {
            initReconfTimeMeasurement(event);
        }

        this.reconfTimeProbe.takeMeasurement();
    }

    /**
     * Initializes reconfiguration time measurement.
     * 
     * TODO StringMeasuringPoint should not be used by SimuLizar. Create something better! I could
     * imagine an EDP2 extension that introduces dedicated reconfiguration measuring points.
     * [Lehrig]
     * 
     * FIXME Dead code; no measurements taken here! Needs some more refactorings. [Lehrig]
     * 
     * @param event
     *            which was fired
     * @param <T>
     *            extends Entity
     */
    private <T extends Entity> void initReconfTimeMeasurement(final ReconfigurationEvent event) {
        this.reconfTimeProbe = new TakeCurrentSimulationTimeProbe(this.simuComModel.getSimulationControl());

        final StringMeasuringPoint measuringPoint = measuringpointFactory.createStringMeasuringPoint();
        measuringPoint.setMeasuringPoint("Reconfiguration");
        // this.calculatorFactory.buildStateOfActiveResourceCalculator(measuringPoint,
        // this.reconfTimeProbe);
    }

    private Boolean simulationIsRunning() {
        return this.simuComModel.getSimulationControl().isRunning();
    }
}
