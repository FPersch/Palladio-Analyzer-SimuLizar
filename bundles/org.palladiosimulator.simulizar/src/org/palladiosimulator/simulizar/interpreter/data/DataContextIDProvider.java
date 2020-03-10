package org.palladiosimulator.simulizar.interpreter.data;

import java.util.concurrent.atomic.AtomicLong;

public class DataContextIDProvider implements IDataContextIDProvider {

	private AtomicLong id;
	
	public DataContextIDProvider() {
		this.id = new AtomicLong();
	}
	
	@Override
	public long getNextId() {
		return id.incrementAndGet();
	}
}
