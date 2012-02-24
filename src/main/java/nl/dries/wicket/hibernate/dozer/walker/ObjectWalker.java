package nl.dries.wicket.hibernate.dozer.walker;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import nl.dries.wicket.hibernate.dozer.SessionFinder;
import nl.dries.wicket.hibernate.dozer.helper.HibernateCollectionType;
import nl.dries.wicket.hibernate.dozer.helper.HibernateProperty;
import nl.dries.wicket.hibernate.dozer.helper.ModelCallback;
import nl.dries.wicket.hibernate.dozer.helper.ReflectionHelper;
import nl.dries.wicket.hibernate.dozer.properties.AbstractPropertyDefinition;
import nl.dries.wicket.hibernate.dozer.properties.CollectionPropertyDefinition;
import nl.dries.wicket.hibernate.dozer.properties.SimplePropertyDefinition;

import org.hibernate.Hibernate;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.HibernateProxyHelper;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.type.AssociationType;
import org.hibernate.type.Type;

/**
 * Walker to traverse an object graph, and remove Hibernate state
 * 
 * @author schulten
 */
public class ObjectWalker<T>
{
	/** Root */
	private final T root;

	/** */
	private final SessionImplementor sessionImpl;

	/** */
	private final SessionFactoryImplementor factory;

	/*** */
	private final ModelCallback callback;

	/** Seen objects, to prevent never ending recursion etc */
	private final Set<Object> seen;

	/**
	 * @param root
	 * @param sessionFinder
	 * @param callback
	 */
	public ObjectWalker(T root, SessionFinder sessionFinder, ModelCallback callback)
	{
		this.root = root;
		this.sessionImpl = (SessionImplementor) sessionFinder.getHibernateSession();
		this.factory = sessionImpl.getFactory();
		this.callback = callback;
		this.seen = new HashSet<>();
	}

	/**
	 * Walk the object tree, handeling registering un-initialized proxies
	 * 
	 * @return the root object
	 */
	public T walk()
	{
		walk(deproxy(root));
		return root;
	}

	/**
	 * Recursive walker
	 * 
	 * @param object
	 *            current object
	 */
	private void walk(Object object)
	{
		Set<Object> toWalk = new HashSet<>();
		ClassMetadata metadata = factory.getClassMetadata(object.getClass());

		if (metadata != null)
		{
			Serializable identifier = metadata.getIdentifier(object, sessionImpl);

			for (String propertyName : metadata.getPropertyNames())
			{
				Type type = metadata.getPropertyType(propertyName);
				if (type instanceof AssociationType)
				{
					Object value = ReflectionHelper.getValue(object, propertyName);

					if (value != null)
					{
						if (!Hibernate.isInitialized(value))
						{
							handleProxy(object, identifier, propertyName, value);
						}
						else if (value instanceof PersistentCollection)
						{
							handleInitializedCollection(object, toWalk,
								convertToPlainCollection(object, propertyName, value));
						}
						else if (value instanceof Collection<?>)
						{
							handlePlainCollection(toWalk, (Collection<?>) value);
						}
						else
						{
							value = deproxy(value);
							if (!seen.contains(value))
							{
								toWalk.add(value);
							}
						}
					}
				}
			}
		}

		seen.add(object);

		for (Iterator<Object> iter = toWalk.iterator(); iter.hasNext();)
		{
			walk(iter.next());
		}
	}

	/**
	 * Add a plain collection to the visitable objects
	 * 
	 * @param toWalk
	 *            set with walkable objects
	 * @param collection
	 *            the collection to add
	 */
	public void handlePlainCollection(Set<Object> toWalk, Collection<?> collection)
	{
		Iterator<?> iter = collection.iterator();
		while (iter.hasNext())
		{
			Object next = iter.next();
			if (!seen.contains(next))
			{
				toWalk.add(next);
			}
		}
	}

	/**
	 * Handles a initialized Hibernate collection value
	 * 
	 * @param object
	 *            the containing object
	 * @param toWalk
	 *            set with objects to visit
	 * @param value
	 *            the collection value
	 */
	public void handleInitializedCollection(Object object, Set<Object> toWalk, Object value)
	{
		if (value instanceof Collection<?>)
		{
			Collection<?> collection = (Collection<?>) value;
			Iterator<?> iter = collection.iterator();
			while (iter.hasNext())
			{
				addObject(toWalk, iter.next());
			}
		}
		else if (value instanceof Map<?, ?>)
		{
			for (Object obj : ((Map<?, ?>) value).entrySet())
			{
				Entry<?, ?> entry = (Entry<?, ?>) obj;

				addObject(toWalk, entry.getKey());
				addObject(toWalk, entry.getValue());
			}
		}
	}

	/**
	 * Add an object to the vistiable objects (only we haven't already seen the object)
	 * 
	 * @param toWalk
	 *            the {@link Set} with objects to visit
	 * @param object
	 *            the object to add to the set
	 */
	public void addObject(Set<Object> toWalk, Object object)
	{
		object = deproxy(object);
		if (!seen.contains(object))
		{
			toWalk.add(object);
		}
	}

	/**
	 * Convert Hibernate collection to a plain collection type
	 * 
	 * @param object
	 *            the object that owns the property
	 * @param propertyName
	 *            the property
	 * @param value
	 *            input collection
	 * @return plain collection type
	 */
	private Object convertToPlainCollection(Object object, String propertyName, Object value)
	{
		PersistentCollection collection = (PersistentCollection) value;
		Object plainCollection = HibernateCollectionType.determineType(collection).createPlainCollection(
			collection);
		ReflectionHelper.setValue(object, propertyName, plainCollection);
		return plainCollection;
	}

	/**
	 * Creates a mapping for a Hibernate proxy
	 * 
	 * @param object
	 *            the owning object
	 * @param identifier
	 *            it's identifier
	 * @param propertyName
	 *            the name of the property
	 * @param value
	 *            its current value
	 */
	@SuppressWarnings("unchecked")
	public void handleProxy(Object object, Serializable identifier, String propertyName, Object value)
	{
		final AbstractPropertyDefinition def;

		Class<? extends Serializable> objectClass = HibernateProxyHelper.getClassWithoutInitializingProxy(object);

		// Collection
		if (value instanceof PersistentCollection)
		{
			def = new CollectionPropertyDefinition(objectClass, identifier, propertyName,
				HibernateCollectionType.determineType((PersistentCollection) value));
		}
		// Other
		else
		{
			LazyInitializer initializer = ((HibernateProxy) value).getHibernateLazyInitializer();
			HibernateProperty property = new HibernateProperty(initializer.getPersistentClass(),
				initializer.getIdentifier());
			def = new SimplePropertyDefinition(objectClass, identifier, propertyName, property);
		}

		callback.addDetachedProperty(object, def);
		ReflectionHelper.setValue(object, propertyName, null); // Reset to null
	}

	/**
	 * Deproxy a Hibernate enhanced object, only call when sure the object is initialized, otherwise (unwanted)
	 * intialization wil take place
	 * 
	 * @param object
	 *            the input object
	 * @return deproxied object
	 */
	@SuppressWarnings("unchecked")
	protected <U> U deproxy(U object)
	{
		if (object instanceof HibernateProxy)
		{
			HibernateProxy hibernateProxy = (HibernateProxy) object;
			LazyInitializer lazyInitializer = hibernateProxy.getHibernateLazyInitializer();

			return (U) lazyInitializer.getImplementation();
		}
		return object;
	}
}
