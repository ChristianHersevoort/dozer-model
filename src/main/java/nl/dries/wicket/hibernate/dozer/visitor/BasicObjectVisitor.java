package nl.dries.wicket.hibernate.dozer.visitor;

import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import nl.dries.wicket.hibernate.dozer.helper.HibernateProperty;
import nl.dries.wicket.hibernate.dozer.helper.ModelCallback;
import nl.dries.wicket.hibernate.dozer.helper.ReflectionHelper;
import nl.dries.wicket.hibernate.dozer.properties.SimplePropertyDefinition;

import org.apache.commons.beanutils.PropertyUtils;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visiting strategy for a plain object
 * 
 * @author dries
 */
public class BasicObjectVisitor implements VisitorStrategy
{
	/** Logger */
	private static final Logger LOG = LoggerFactory.getLogger(BasicObjectVisitor.class);

	/** Session implementor */
	private final SessionImplementor sessionImpl;

	/** Callback */
	private final ModelCallback callback;

	/**
	 * Construct
	 * 
	 * @param sessionImpl
	 * @param callback
	 */
	public BasicObjectVisitor(SessionImplementor sessionImpl, ModelCallback callback)
	{
		this.sessionImpl = sessionImpl;
		this.callback = callback;
	}

	/**
	 * @see nl.dries.wicket.hibernate.dozer.visitor.VisitorStrategy#visit(java.lang.Object)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Set<Object> visit(Object object)
	{
		Set<Object> toWalk = new HashSet<>();

		for (PropertyDescriptor descriptor : PropertyUtils.getPropertyDescriptors(object.getClass()))
		{
			Class<?> type = descriptor.getPropertyType();

			if (!type.isPrimitive())
			{
				Object value = getValue(descriptor.getReadMethod(), object);
				if (value != null && !(value instanceof Class<?>))
				{
					ClassMetadata metadata = sessionImpl.getFactory().getClassMetadata(type);
					if (metadata == null)
					{
						toWalk.add(value);
					}
					else
					{
						if (Hibernate.isInitialized(value))
						{
							toWalk.add(ReflectionHelper.deproxy(value));
						}
						else
						{
							LazyInitializer initializer = ((HibernateProxy) value).getHibernateLazyInitializer();
							HibernateProperty property = new HibernateProperty(initializer.getPersistentClass(),
								initializer.getIdentifier());
							callback.addDetachedProperty(object, new SimplePropertyDefinition(
								(Class<? extends Serializable>) object.getClass(), null,
								descriptor.getName(), property));
							resetValue(descriptor.getWriteMethod(), object);
						}
					}
				}
			}
		}

		return toWalk;
	}

	/**
	 * Get a value by invoking a getter
	 * 
	 * @param method
	 *            the get method
	 * @param object
	 *            the object to invoke the getter on
	 * @return retrieved value
	 */
	private Object getValue(Method method, Object object)
	{
		try
		{
			return method.invoke(object);
		}
		catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
		{
			LOG.error(String.format("Error while invoking getter %s on bean %s", method, object), e);
		}

		return null;
	}

	/**
	 * Resets a field
	 * 
	 * @param method
	 *            using this setter method
	 * @param object
	 *            on this object
	 */
	private void resetValue(Method method, Object object)
	{
		try
		{
			method.invoke(object, new Object[] { null });
		}
		catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
		{
			LOG.error(String.format("Error while invoking reset method %s on bean %s", method, object), e);
		}
	}
}
