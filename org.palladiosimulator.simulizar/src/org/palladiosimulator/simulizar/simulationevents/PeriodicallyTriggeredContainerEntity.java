package org.palladiosimulator.simulizar.simulationevents;

import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.palladiosimulator.commons.designpatterns.AbstractObservable;
import org.palladiosimulator.commons.designpatterns.IAbstractObservable;
import org.palladiosimulator.mdsdprofiles.api.StereotypeAPI;
import org.palladiosimulator.pcm.resourceenvironment.ResourceContainer;
import org.palladiosimulator.simulizar.runtimestate.CostModel;

import de.uka.ipd.sdq.simucomframework.model.SimuComModel;

/**
 * An entity that can trigger periodic events with an attached container.
 *
 * @author Hendrik Eikerling, Sebastian Lehrig
 *
 */
public class PeriodicallyTriggeredContainerEntity extends PeriodicallyTriggeredSimulationEntity
        implements IAbstractObservable<IAbstractPeriodicContainerListener> {

    private static final Logger LOGGER = Logger.getLogger(PeriodicallyTriggeredSimulationEntity.class);
    private final ResourceContainer resourceContainer;
    private final CostModel costModel;

    private final AbstractObservable<IAbstractPeriodicContainerListener> observableDelegate;
    private Set<ResourceContainer> containerSet;

    public PeriodicallyTriggeredContainerEntity(final SimuComModel model, final CostModel costModel,
            final double firstOccurrence, final double delay, final ResourceContainer resourceContainer) {
        super(model, firstOccurrence, delay);
        this.resourceContainer = resourceContainer;
        this.costModel = costModel;
        this.observableDelegate = new AbstractObservable<IAbstractPeriodicContainerListener>() {
        };

    }

    @Override
    protected void triggerInternal() {
        final Double timestamp = this.getModel().getSimulationControl().getCurrentSimulationTime();

        if (LOGGER.isInfoEnabled()) {
            final StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("Periodic trigger for container ");
            stringBuilder.append(this.resourceContainer.getEntityName());
            stringBuilder.append(" occured at time ");
            stringBuilder.append(timestamp.toString());
            LOGGER.info(stringBuilder.toString());
        }

        final double containerPrice;
        this.containerSet = new HashSet<ResourceContainer>();
        this.containerSet.add(this.resourceContainer);

        if (StereotypeAPI.hasAppliedStereotype(this.containerSet, "price")) {
            containerPrice = StereotypeAPI.getTaggedValue(this.resourceContainer, "price", "price");
        } else {
            containerPrice = 9.99;
        }

        this.costModel.addCostTuple(this.resourceContainer.getId(), timestamp, new Double(containerPrice));
    }

    @Override
    public void addObserver(final IAbstractPeriodicContainerListener observer) {
        this.observableDelegate.addObserver(observer);
    }

    @Override
    public void removeObserver(final IAbstractPeriodicContainerListener observer) {
        this.observableDelegate.removeObserver(observer);
    }

    @Override
    public void removeEvent() {
        super.removeEvent();
    }
}
