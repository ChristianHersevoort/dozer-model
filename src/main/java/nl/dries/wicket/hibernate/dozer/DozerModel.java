package nl.dries.wicket.hibernate.dozer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import nl.dries.wicket.hibernate.dozer.helper.Attacher;
import nl.dries.wicket.hibernate.dozer.helper.HibernateFieldMapper;
import nl.dries.wicket.hibernate.dozer.helper.PropertyDefinition;

import org.apache.wicket.injection.Injector;
import org.apache.wicket.model.IModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.dozer.DozerBeanMapper;
import org.dozer.Mapper;
import org.dozer.util.DozerConstants;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dozer Wicket Hibernate model. This model wil act as a detachable model. When detaching all initalized objects in the
 * object graph are cloned/copied using Dozer. This way all Hibernate information (initializer containing session state)
 * is discarded, so when reattaching the object we won't get Lazy/session closed exceptions.<br>
 * <br>
 * All un-initalized proxies in the object graph are saved to lightweight pointers (not containing Hibernate state).
 * When the object is re-attached these pointers will be restored as 'normal' Hibernate proxies that will get
 * initialized on access.
 * 
 * @author dries
 * 
 * @param <T>
 *            type of model object
 */
public class DozerModel<T> implements IModel<T>
{
	/** Default */
	private static final long serialVersionUID = 1L;

	/** Logger */
	private static final Logger LOG = LoggerFactory.getLogger(DozerModel.class);

	/** Object instance */
	private T object;

	/** Detached object instance */
	private T detachedObject;

	/** The object's {@link Class} */
	private Class<T> objectClass;

	/** Map containing detached properties */
	private Map<Object, List<PropertyDefinition>> detachedProperties;

	/** */
	@SpringBean
	private SessionFinder sessionFinder;

	/**
	 * Construct
	 * 
	 * @param object
	 */
	@SuppressWarnings("unchecked")
	public DozerModel(T object)
	{
		this();

		this.object = object;

		if (object != null)
		{
			if (object.getClass().getName().contains(DozerConstants.CGLIB_ID))
			{
				LOG.warn("Given object is a Cglib proxy this can cause unexpected behavior, " + object);
			}

			this.objectClass = Hibernate.getClass(object);
		}
	}

	/**
	 * Construct with class/id, <b>will directly load the object!!!</b> (but not initialize it)
	 * 
	 * @param objectClass
	 * @param id
	 */
	@SuppressWarnings("unchecked")
	public DozerModel(Class<T> objectClass, Serializable id)
	{
		this();

		this.object = (T) sessionFinder.getSession().load(objectClass, id);
		this.objectClass = objectClass;
	}

	/**
	 * Construct
	 */
	public DozerModel()
	{
		Injector.get().inject(this);
	}

	/**
	 * @see org.apache.wicket.model.IModel#getObject()
	 */
	@Override
	public T getObject()
	{
		List<Attacher<Object>> list = buildAttachers();

		// Possibly restore detached state
		if (object == null && detachedObject != null)
		{
			object = detachedObject;

			if (list != null)
			{
				for (Attacher<Object> attacher : list)
				{
					attacher.doAttachProperties();
				}
			}

			// Remove detached state
			detachedObject = null;
		}

		// We always need to re-attach unintialized collections, when Hiberate flushes the collections are re-wrapped
		// (creating new collection proxies) this means that is is possible that a flush has happend between our 2
		// getObject calls within the same request-cycle and thus invalidating or created collection proxy.
		if (list != null)
		{
			for (Attacher<Object> attacher : list)
			{
				attacher.doAttachCollections();
			}
		}

		return object;
	}

	/**
	 * @return set with {@link Attacher}s
	 */
	private List<Attacher<Object>> buildAttachers()
	{
		List<Attacher<Object>> list = null;

		if (detachedProperties != null)
		{
			list = new ArrayList<>(detachedProperties.size());

			// Re-attach properties
			for (Entry<Object, List<PropertyDefinition>> entry : detachedProperties.entrySet())
			{
				list.add(new Attacher<Object>(entry.getKey(), sessionFinder.getSession(), entry.getValue()));
			}
		}

		return list;
	}

	/**
	 * @see org.apache.wicket.model.IModel#setObject(java.lang.Object)
	 */
	@Override
	public void setObject(T object)
	{
		this.object = object;
	}

	/**
	 * @see org.apache.wicket.model.IDetachable#detach()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void detach()
	{
		if (object != null && detachedObject == null)
		{
			if (object instanceof HibernateProxy)
			{
				HibernateProxy proxy = (HibernateProxy) object;
				object = (T) proxy.getHibernateLazyInitializer().getImplementation();
			}

			DozerBeanMapper mapper = createMapper();
			detachedObject = mapper.map(object, objectClass);
			object = null;

			if (LOG.isDebugEnabled())
			{
				StringBuilder sb = new StringBuilder("Detached model state for type ");
				sb.append(objectClass.getName());
				sb.append(", detached Hibernate properties: ");

				if (detachedProperties != null)
				{
					for (Entry<Object, List<PropertyDefinition>> entry : detachedProperties.entrySet())
					{
						sb.append("\n").append(entry.getKey()).append(": [").append(entry.getValue()).append("]");
					}
				}

				LOG.debug(sb.toString());
			}
		}
	}

	/**
	 * Add a detached property
	 * 
	 * @param object
	 *            the owner (Dozer converted instance, <b>NO</b> Hibernate proxy)
	 * @param property
	 *            the {@link PropertyDefinition} it maps to
	 */
	public void addDetachedProperty(Object owner, PropertyDefinition def)
	{
		if (detachedProperties == null)
		{
			detachedProperties = new HashMap<>();
		}

		if (!detachedProperties.containsKey(owner))
		{
			detachedProperties.put(owner, new ArrayList<PropertyDefinition>());
		}

		detachedProperties.get(owner).add(def);
	}

	/**
	 * @return {@link Mapper} instance
	 */
	private DozerBeanMapper createMapper()
	{
		DozerBeanMapper mapper = new DozerBeanMapper();
		mapper.setCustomFieldMapper(new HibernateFieldMapper(this));
		return mapper;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((getObject() == null) ? 0 : getObject().hashCode());
		return result;
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj == null)
		{
			return false;
		}
		if (obj.getClass().isAssignableFrom(IModel.class))
		{
			return false;
		}
		IModel<T> other = (IModel<T>) obj;
		if (getObject() == null)
		{
			if (other.getObject() != null)
			{
				return false;
			}
		}
		else if (!getObject().equals(other.getObject()))
		{
			return false;
		}
		return true;
	}
}
