/*******************************************************************************
 * Copyright (c) 1998, 2008 Oracle. All rights reserved.
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 
 * which accompanies this distribution. 
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Oracle - initial API and implementation from Oracle TopLink
 ******************************************************************************/  
package org.eclipse.persistence.mappings;

import java.util.*;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.exceptions.*;
import org.eclipse.persistence.expressions.*;
import org.eclipse.persistence.internal.helper.*;
import org.eclipse.persistence.internal.identitymaps.*;
import org.eclipse.persistence.internal.indirection.ProxyIndirectionPolicy;
import org.eclipse.persistence.internal.queries.ContainerPolicy;
import org.eclipse.persistence.internal.queries.JoinedAttributeManager;
import org.eclipse.persistence.internal.sessions.*;
import org.eclipse.persistence.sessions.DatabaseRecord;
import org.eclipse.persistence.queries.*;
import org.eclipse.persistence.internal.descriptors.CascadeLockingPolicy;
import org.eclipse.persistence.internal.descriptors.DescriptorIterator;
import org.eclipse.persistence.internal.expressions.SQLSelectStatement;
import org.eclipse.persistence.mappings.foundation.MapKeyMapping;

/**
 * <p><b>Purpose</b>: One to one mappings are used to represent a pointer references
 * between two java objects. This mappings is usually represented by a single pointer
 * (stored in an instance variable) between the source and target objects. In the relational
 * database tables, these mappings are normally implemented using foreign keys.
 *
 * @author Sati
 * @since TOPLink/Java 1.0
 */
public class OneToOneMapping extends ObjectReferenceMapping implements RelationalMapping, MapKeyMapping {

    /** Maps the source foreign/primary key fields to the target primary/foreign key fields. */
    protected Map<DatabaseField, DatabaseField> sourceToTargetKeyFields;

    /** Maps the target primary/foreign key fields to the source foreign/primary key fields. */
    protected Map<DatabaseField, DatabaseField> targetToSourceKeyFields;

    /** Keeps track of which fields are foreign keys on a per field basis (can have mixed foreign key relationships). */
    /** These are used for non-unit of work modification to check if the value of the 1-1 was changed and a deletion is required. */
    protected boolean shouldVerifyDelete;
    protected transient Expression privateOwnedCriteria;
    
    public DatabaseTable keyTableForMapKey = null;

    /**
     * PUBLIC:
     * Default constructor.
     */
    public OneToOneMapping() {
        this.selectionQuery = new ReadObjectQuery();
        this.sourceToTargetKeyFields = new HashMap(2);
        this.targetToSourceKeyFields = new HashMap(2);
        this.foreignKeyFields = org.eclipse.persistence.internal.helper.NonSynchronizedVector.newInstance(1);
        this.isForeignKeyRelationship = false;
        this.shouldVerifyDelete = true;
    }

    /**
     * INTERNAL:
     */
    public boolean isRelationalMapping() {
        return true;
    }

    /**
     * INTERNAL:
     * Used when initializing queries for mappings that use a Map
     * Called when the selection query is being initialized to add the fields for the map key to the query
     */
    public void addAdditionalFieldsToQuery(ReadQuery selectionQuery, Expression baseExpression){
        Iterator i = getForeignKeyFields().iterator();
        while (i.hasNext()){
            DatabaseField field = (DatabaseField)i.next();
            if (selectionQuery.isObjectLevelReadQuery()){
                if (baseExpression != null){
                    ((ObjectLevelReadQuery)selectionQuery).addAdditionalField(baseExpression.getField(field));
                } else {
                    ((ObjectLevelReadQuery)selectionQuery).addAdditionalField(field);
                }
            } else if (selectionQuery.isDataReadQuery()){
                ((SQLSelectStatement)((DataReadQuery)selectionQuery).getSQLStatement()).addField(field);
            }
        }
    }
    
    /**
     * INTERNAL:
     * Used when initializing queries for mappings that use a Map
     * Called when the insert query is being initialized to ensure the fields for the map key are in the insert query
     */
    public void addFieldsForMapKey(AbstractRecord joinRow){
        Iterator i = getForeignKeyFields().iterator();
        while (i.hasNext()){
            joinRow.put((DatabaseField)i.next(), null);
        }
    }
    
    /**
     * PUBLIC:
     * Define the foreign key relationship in the 1-1 mapping.
     * This method is used for composite foreign key relationships,
     * that is the source object's table has multiple foreign key fields to
     * the target object's primary key fields.
     * Both the source foreign key field and the target foreign key field must 
     * be specified.
     * When a foreign key is specified TopLink will automatically populate the 
     * value for that field from the target object when the object is written to 
     * the database. If the foreign key is also mapped through a direct-to-field 
     * then the direct-to-field must be set read-only.
     */
    public void addForeignKeyField(DatabaseField sourceForeignKeyField, DatabaseField targetPrimaryKeyField) {
        setIsForeignKeyRelationship(true);
        getForeignKeyFields().addElement(sourceForeignKeyField);

        getSourceToTargetKeyFields().put(sourceForeignKeyField, targetPrimaryKeyField);
        getTargetToSourceKeyFields().put(targetPrimaryKeyField, sourceForeignKeyField);
    }
    
    /**
     * PUBLIC:
     * Define the foreign key relationship in the 1-1 mapping.
     * This method is used for composite foreign key relationships,
     * that is the source object's table has multiple foreign key fields to
     * the target object's primary key fields.
     * Both the source foreign key field name and the target foreign key field 
     * name must be specified.
     * When a foreign key is specified TopLink will automatically populate the 
     * value for that field from the target object when the object is written to 
     * the database. If the foreign key is also mapped through a direct-to-field 
     * then the direct-to-field must be set read-only.
     */
    public void addForeignKeyFieldName(String sourceForeignKeyFieldName, String targetPrimaryKeyFieldName) {
        addForeignKeyField(new DatabaseField(sourceForeignKeyFieldName), new DatabaseField(targetPrimaryKeyFieldName));
    }

    /**
     * PUBLIC:
     * Define the target foreign key relationship in the 1-1 mapping.
     * This method is used for composite target foreign key relationships,
     * that is the target object's table has multiple foreign key fields to
     * the source object's primary key fields.
     * Both the target foreign key field and the source primary key field must 
     * be specified.
     * The distinction between a foreign key and target foreign key is that the 
     * 1-1 mapping will not populate the target foreign key value when written 
     * (because it is in the target table). Normally 1-1's are through foreign 
     * keys but in bi-directional 1-1's the back reference will be a target 
     * foreign key. In obscure composite legacy data models a 1-1 may consist of 
     * a foreign key part and a target foreign key part, in this case both 
     * method will be called with the correct parts.
     */
    public void addTargetForeignKeyField(DatabaseField targetForeignKeyField, DatabaseField sourcePrimaryKeyField) {
        getSourceToTargetKeyFields().put(sourcePrimaryKeyField, targetForeignKeyField);
        getTargetToSourceKeyFields().put(targetForeignKeyField, sourcePrimaryKeyField);
    }

    /**
     * PUBLIC:
     * Define the target foreign key relationship in the 1-1 mapping.
     * This method is used for composite target foreign key relationships,
     * that is the target object's table has multiple foreign key fields to
     * the source object's primary key fields.
     * Both the target foreign key field name and the source primary key field 
     * name must be specified.
     * The distinction between a foreign key and target foreign key is that the 
     * 1-1 mapping will not populate the target foreign key value when written 
     * (because it is in the target table). Normally 1-1's are through foreign 
     * keys but in bi-directional 1-1's the back reference will be a target 
     * foreign key. In obscure composite legacy data models a 1-1 may consist of 
     * a foreign key part and a target foreign key part, in this case both 
     * method will be called with the correct parts.
     */
    public void addTargetForeignKeyFieldName(String targetForeignKeyFieldName, String sourcePrimaryKeyFieldName) {
        addTargetForeignKeyField(new DatabaseField(targetForeignKeyFieldName), new DatabaseField(sourcePrimaryKeyFieldName));
    }

    /**
     * INTERNAL:
     * For mappings used as MapKeys in MappedKeyContainerPolicy.  Add the target of this mapping to the deleted 
     * objects list if necessary
     *
     * This method is used for removal of private owned relationships
     * 
     * @param object
     * @param manager
     */
    public void addKeyToDeletedObjectsList(Object object, Map deletedObjects){
        deletedObjects.put(object, object);
    }
    
    /**
     * Build a clone of the given element in a unitOfWork
     * @param element
     * @param unitOfWork
     * @param isExisting
     * @return
     */
    public Object buildElementClone(Object attributeValue, Object parent, UnitOfWorkImpl unitOfWork, boolean isExisting){
        return buildCloneForPartObject(attributeValue, null, null, unitOfWork, isExisting);
    }
    
    /**
     * INTERNAL:
     * Used to allow object level comparisons.
     */
    public Expression buildObjectJoinExpression(Expression expression, Object value, AbstractSession session) {
        Expression base = ((org.eclipse.persistence.internal.expressions.ObjectExpression)expression).getBaseExpression();
        Expression foreignKeyJoin = null;

        // Allow for equal null.
        if (value == null) {
            // Can only perform null comparison on foreign key relationships.
            // It does not really make sense for target any way as it is the source key.
            if (!isForeignKeyRelationship()) {
                throw QueryException.cannotCompareTargetForeignKeysToNull(base, value, this);
            }
            for (Iterator sourceFieldsEnum = getSourceToTargetKeyFields().keySet().iterator();
                     sourceFieldsEnum.hasNext();) {
                DatabaseField field = (DatabaseField)sourceFieldsEnum.next();
                Expression join = null;
                join = base.getField(field).equal(null);
                if (foreignKeyJoin == null) {
                    foreignKeyJoin = join;
                } else {
                    foreignKeyJoin = foreignKeyJoin.and(join);
                }
            }
        } else {
            if (!getReferenceDescriptor().getJavaClass().isInstance(value)) {
                // Bug 3894351 - ensure any proxys are triggered so we can do a proper class comparison
                value = ProxyIndirectionPolicy.getValueFromProxy(value);
                if (!getReferenceDescriptor().getJavaClass().isInstance(value)) {
                    throw QueryException.incorrectClassForObjectComparison(base, value, this);
                }
            }

            Enumeration keyEnum = extractKeyFromReferenceObject(value, session).elements();
            for (Iterator sourceFieldsEnum = getSourceToTargetKeyFields().keySet().iterator();
                     sourceFieldsEnum.hasNext();) {
                DatabaseField field = (DatabaseField)sourceFieldsEnum.next();
                Expression join = null;
                join = base.getField(field).equal(keyEnum.nextElement());
                if (foreignKeyJoin == null) {
                    foreignKeyJoin = join;
                } else {
                    foreignKeyJoin = foreignKeyJoin.and(join);
                }
            }
        }
        return foreignKeyJoin;
    }

    /**
     * INTERNAL:
     * Used to allow object level comparisons.
     */
    public Expression buildObjectJoinExpression(Expression expression, Expression argument, AbstractSession session) {
        Expression base = ((org.eclipse.persistence.internal.expressions.ObjectExpression)expression).getBaseExpression();
        Expression foreignKeyJoin = null;
        if (expression==argument){
            for (Iterator sourceFieldsEnum = getSourceToTargetKeyFields().keySet().iterator();
                     sourceFieldsEnum.hasNext();) {
                DatabaseField field = (DatabaseField)sourceFieldsEnum.next();
                Expression join = base.getField(field);
                join = join.equal(join);
                if (foreignKeyJoin == null) {
                    foreignKeyJoin = join;
                } else {
                    foreignKeyJoin = foreignKeyJoin.and(join);
                }
            }
        }else{
            Iterator targetFieldsEnum = getSourceToTargetKeyFields().values().iterator();
            for (Iterator sourceFieldsEnum = getSourceToTargetKeyFields().keySet().iterator();
                     sourceFieldsEnum.hasNext();) {
                DatabaseField sourceField = (DatabaseField)sourceFieldsEnum.next();
                DatabaseField targetField = (DatabaseField)targetFieldsEnum.next();
                Expression join = null;
                join = base.getField(sourceField).equal(argument.getField(targetField));
                if (foreignKeyJoin == null) {
                    foreignKeyJoin = join;
                } else {
                    foreignKeyJoin = foreignKeyJoin.and(join);
                }
            }
        }
        return foreignKeyJoin;
    }

    /**
     * INTERNAL:
     * Certain key mappings favor different types of selection query.  Return the appropriate
     * type of selectionQuery
     * @return
     */
    public ReadQuery buildSelectionQueryForDirectCollectionKeyMapping(ContainerPolicy containerPolicy){
        DataReadQuery query = new DataReadQuery();
        query.setSQLStatement(new SQLSelectStatement());
        query.setContainerPolicy(containerPolicy);
        return query;
    }
    
    /**
     * INTERNAL:
     * This methods clones all the fields and ensures that each collection refers to
     * the same clones.
     */
    public Object clone() {
        OneToOneMapping clone = (OneToOneMapping)super.clone();
        clone.setForeignKeyFields(org.eclipse.persistence.internal.helper.NonSynchronizedVector.newInstance(getForeignKeyFields().size()));
        clone.setSourceToTargetKeyFields(new HashMap(getSourceToTargetKeyFields().size()));
        clone.setTargetToSourceKeyFields(new HashMap(getTargetToSourceKeyFields().size()));
        Hashtable setOfFields = new Hashtable(getTargetToSourceKeyFields().size());

        //clone foreign keys and save the clones in a set
        for (Enumeration enumtr = getForeignKeyFields().elements(); enumtr.hasMoreElements();) {
            DatabaseField field = (DatabaseField)enumtr.nextElement();
            DatabaseField fieldClone = (DatabaseField)field.clone();
            setOfFields.put(field, fieldClone);
            clone.getForeignKeyFields().addElement(fieldClone);
        }

        //get clones from set for source hashtable.  If they do not exist, create a new one.
        for (Iterator sourceEnum = getSourceToTargetKeyFields().keySet().iterator();
                 sourceEnum.hasNext();) {
            DatabaseField sourceField = (DatabaseField)sourceEnum.next();
            DatabaseField targetField = getSourceToTargetKeyFields().get(sourceField);

            DatabaseField targetClone;
            DatabaseField sourceClone;

            targetClone = (DatabaseField)setOfFields.get(targetField);
            if (targetClone == null) {
                targetClone = (DatabaseField)targetField.clone();
                setOfFields.put(targetField, targetClone);
            }
            sourceClone = (DatabaseField)setOfFields.get(sourceField);
            if (sourceClone == null) {
                sourceClone = (DatabaseField)sourceField.clone();
                setOfFields.put(sourceField, sourceClone);
            }
            clone.getSourceToTargetKeyFields().put(sourceClone, targetClone);
        }

        //get clones from set for target hashtable.  If they do not exist, create a new one.
        for (Iterator targetEnum = getTargetToSourceKeyFields().keySet().iterator();
                 targetEnum.hasNext();) {
            DatabaseField targetField = (DatabaseField)targetEnum.next();
            DatabaseField sourceField = getTargetToSourceKeyFields().get(targetField);

            DatabaseField targetClone;
            DatabaseField sourceClone;

            targetClone = (DatabaseField)setOfFields.get(targetField);
            if (targetClone == null) {
                targetClone = (DatabaseField)targetField.clone();
                setOfFields.put(targetField, targetClone);
            }
            sourceClone = (DatabaseField)setOfFields.get(sourceField);
            if (sourceClone == null) {
                sourceClone = (DatabaseField)sourceField.clone();
                setOfFields.put(sourceField, sourceClone);
            }
            clone.getTargetToSourceKeyFields().put(targetClone, sourceClone);
        }
        return clone;
    }
    
    /**
     * INTERNAL
     * Called when a DatabaseMapping is used to map the key in a collection.  Returns the key.
     */
    public Object createMapComponentFromRow(AbstractRecord dbRow, ObjectBuildingQuery query, AbstractSession session){
        return session.executeQuery(getSelectionQuery(), dbRow);
    }

    /**
     * INTERNAL
     * Called when a DatabaseMapping is used to map the key in a collection.  Returns the key.
     */
    public Object createMapComponentFromJoinedRow(AbstractRecord dbRow, JoinedAttributeManager joinManager, ObjectBuildingQuery query, AbstractSession session){
        return valueFromRowInternalWithJoin(dbRow, joinManager, query, session);
    }
    
    /**
     * INTERNAL:
     * For mappings used as MapKeys in MappedKeyContainerPolicy, Delete the passed object if necessary.
     * 
     * This method is used for removal of private owned relationships
     * 
     * @param objectDeleted
     * @param session
     */
    public void deleteMapKey(Object objectDeleted, AbstractSession session){
        session.deleteObject(objectDeleted);
    }
    
    /**
     * INTERNAL:
     * Extract the foreign key value from the source row.
     */
    protected Vector extractForeignKeyFromRow(AbstractRecord row, AbstractSession session) {
        Vector key = new Vector();

        for (Iterator fieldEnum = getSourceToTargetKeyFields().keySet().iterator();
                 fieldEnum.hasNext();) {
            DatabaseField field = (DatabaseField)fieldEnum.next();
            Object value = row.get(field);

            // Must ensure the classification gets a cache hit.
            try {
                value = session.getDatasourcePlatform().getConversionManager().convertObject(value, getDescriptor().getObjectBuilder().getFieldClassification(field));
            } catch (ConversionException e) {
                throw ConversionException.couldNotBeConverted(this, getDescriptor(), e);
            }

            key.addElement(value);
        }

        return key;
    }

    
    /**
     * INTERNAL:
     * Extract the fields for the Map key from the object to use in a query
     * @return
     */
    public Map extractIdentityFieldsForQuery(Object object, AbstractSession session){
        Map keyFields = new HashMap();
         for (int index = 0; index < getForeignKeyFields().size(); index++) {
            DatabaseField targetRelationField = getForeignKeyFields().elementAt(index);
            DatabaseField targetKey = getSourceToTargetKeyFields().get(targetRelationField);
            Object value = getReferenceDescriptor().getObjectBuilder().extractValueFromObjectForField(object, targetKey, session);
            keyFields.put(targetRelationField, value);
        }
        return keyFields;
    }
    
    /**
     * INTERNAL:
     * Extract the key value from the reference object.
     */
    protected Vector extractKeyFromReferenceObject(Object object, AbstractSession session) {
        Vector key = new Vector();

        for (Iterator fieldEnum = getSourceToTargetKeyFields().values().iterator();
                 fieldEnum.hasNext();) {
            DatabaseField field = (DatabaseField)fieldEnum.next();

            if (object == null) {
                key.addElement(null);
            } else {
                key.addElement(getReferenceDescriptor().getObjectBuilder().extractValueFromObjectForField(object, field, session));
            }
        }

        return key;
    }

    /**
     * INTERNAL:
     *    Return the primary key for the reference object (i.e. the object
     * object referenced by domainObject and specified by mapping).
     * This key will be used by a RemoteValueHolder.
     */
    public Vector extractPrimaryKeysForReferenceObjectFromRow(AbstractRecord row) {
        List primaryKeyFields = getReferenceDescriptor().getPrimaryKeyFields();
        Vector result = new Vector(primaryKeyFields.size());
        for (int index = 0; index < primaryKeyFields.size(); index++) {
            DatabaseField targetKeyField = (DatabaseField)primaryKeyFields.get(index);
            DatabaseField sourceKeyField = getTargetToSourceKeyFields().get(targetKeyField);
            if (sourceKeyField == null) {
                return new Vector(1);
            }
            result.addElement(row.get(sourceKeyField));
        }
        return result;
    }

    /**
     * INTERNAL:
     * Allow the mapping the do any further batch preparation.
     */
    protected void postPrepareNestedBatchQuery(ReadQuery batchQuery, ReadAllQuery query) {
        // Force a distinct to filter out m-1 duplicates.
        if (!query.isDistinctComputed()) {
            ((ObjectLevelReadQuery)batchQuery).useDistinct();
        }
    }
    
    /**
     * INTERNAL:
     * Extract the value from the batch optimized query.
     */
    public Object extractResultFromBatchQuery(DatabaseQuery query, AbstractRecord databaseRow, AbstractSession session, AbstractRecord argumentRow) {
        //this can be null, because either one exists in the query or it will be created
        Hashtable referenceObjectsByKey = null;
        synchronized (query) {
            referenceObjectsByKey = getBatchReadObjects(query, session);
            if (referenceObjectsByKey == null) {
                Vector results = (Vector)session.executeQuery(query, argumentRow);

                referenceObjectsByKey = new Hashtable();

                for (Enumeration enumeration = results.elements(); enumeration.hasMoreElements();) {
                    Object eachReferenceObject = enumeration.nextElement();
                    CacheKey eachReferenceKey = new CacheKey(extractKeyFromReferenceObject(eachReferenceObject, session));

                    referenceObjectsByKey.put(eachReferenceKey, session.wrapObject(eachReferenceObject));
                }
                setBatchReadObjects(referenceObjectsByKey, query, session);
            }
        }

        return referenceObjectsByKey.get(new CacheKey(extractForeignKeyFromRow(databaseRow, session)));
    }

    
    /**
     * INTERNAL:
     * Return the selection criteria necessary to select the target object when this mapping
     * is a map key.
     * @return
     */
    public Expression getAdditionalSelectionCriteriaForMapKey(){
        return buildSelectionCriteria(false, false);
    }

    /**
     * INTERNAL:
     * Return any tables that will be required when this mapping is used as part of a join query
     * @return
     */
    public List<DatabaseTable> getAdditionalTablesForJoinQuery(){
        List<DatabaseTable> tables = new ArrayList<DatabaseTable>(getReferenceDescriptor().getTables().size() + 1);
        tables.addAll(getReferenceDescriptor().getTables());
        if (keyTableForMapKey != null){
            tables.add(keyTableForMapKey);
        }
        return tables;
    }
    
    /**
     * INTERNAL:
     * Return the classifiction for the field contained in the mapping.
     * This is used to convert the row value to a consistent java value.
     */
    public Class getFieldClassification(DatabaseField fieldToClassify) throws DescriptorException {
        DatabaseField fieldInTarget = getSourceToTargetKeyFields().get(fieldToClassify);
        if (fieldInTarget == null) {
            return null;// Can be registered as multiple table secondary field mapping
        }
        DatabaseMapping mapping = getReferenceDescriptor().getObjectBuilder().getMappingForField(fieldInTarget);
        if (mapping == null) {
            return null;// Means that the mapping is read-only
        }
        return mapping.getFieldClassification(fieldInTarget);
    }

    /**
     * PUBLIC:
     * Return the foreign key field names associated with the mapping.
     * These are only the source fields that are writable.
     */
    public Vector getForeignKeyFieldNames() {
        Vector fieldNames = new Vector(getForeignKeyFields().size());
        for (Enumeration fieldsEnum = getForeignKeyFields().elements();
                 fieldsEnum.hasMoreElements();) {
            fieldNames.addElement(((DatabaseField)fieldsEnum.nextElement()).getQualifiedName());
        }

        return fieldNames;
    }

    /**
     * Return the appropriate hashtable that maps the "foreign keys"
     * to the "primary keys".
     */
    protected Map getForeignKeysToPrimaryKeys() {
        if (this.isForeignKeyRelationship()) {
            return this.getSourceToTargetKeyFields();
        } else {
            return this.getTargetToSourceKeyFields();
        }
    }


    /**
     * INTERNAL:
     * Return the fields that make up the identity of the mapped object.  For mappings with
     * a primary key, it will be the set of fields in the primary key.  For mappings without
     * a primary key it will likely be all the fields
     * @return
     */
    public List<DatabaseField> getIdentityFieldsForMapKey(){
            return getForeignKeyFields();
    }
    
    
    /**
     * INTERNAL:
     * Return the query that is used when this mapping is part of a joined relationship
     * 
     * This method is used when this mapping is used to map the key in a Map
     * @return
     */
    public ObjectLevelReadQuery getNestedJoinQuery(JoinedAttributeManager joinManager, ObjectLevelReadQuery query, AbstractSession session){
        return prepareNestedJoins(joinManager, query, session);
    }
    
    /**
     * INTERNAL:
     * Get all the fields for the map key
     */
    public List<DatabaseField> getAllFieldsForMapKey(){
        List<DatabaseField> fields = new ArrayList(getReferenceDescriptor().getAllFields().size() + getForeignKeyFields().size());
        fields.addAll(getReferenceDescriptor().getAllFields());
        fields.addAll(getForeignKeyFields());
        return fields;
    }
    
    /**
     * INTERNAL:
     * Return a vector of the foreign key fields in the same order
     * as the corresponding primary key fields are in their descriptor.
     */
    public Vector getOrderedForeignKeyFields() {
        List primaryKeyFields = getPrimaryKeyDescriptor().getPrimaryKeyFields();
        Vector result = new Vector(primaryKeyFields.size());

        for (int index = 0; index < primaryKeyFields.size(); index++) {
            DatabaseField pkField = (DatabaseField)primaryKeyFields.get(index);
            boolean found = false;
            for (Iterator fkStream = this.getForeignKeysToPrimaryKeys().keySet().iterator();
                     fkStream.hasNext();) {
                DatabaseField fkField = (DatabaseField)fkStream.next();

                if (this.getForeignKeysToPrimaryKeys().get(fkField).equals(pkField)) {
                    found = true;
                    result.addElement(fkField);
                    break;
                }
            }
            if (!found) {
                throw DescriptorException.missingForeignKeyTranslation(this, pkField);
            }
        }
        return result;
    }

    /**
     * Return the descriptor for whichever side of the
     * relation has the "primary key".
    */
    protected ClassDescriptor getPrimaryKeyDescriptor() {
        if (this.isForeignKeyRelationship()) {
            return this.getReferenceDescriptor();
        } else {
            return this.getDescriptor();
        }
    }

    /**
     * INTERNAL:
     * The private owned criteria is only used outside of the unit of work to compare the previous value of the reference.
     */
    public Expression getPrivateOwnedCriteria() {
        if (privateOwnedCriteria == null) {
            initializePrivateOwnedCriteria();
        }
        return privateOwnedCriteria;
    }
    
    /**
     * INTERNAL:
     * Return a collection of the source to target field value associations.
     */
    public Vector getSourceToTargetKeyFieldAssociations() {
        Vector associations = new Vector(getSourceToTargetKeyFields().size());
        Iterator sourceFieldEnum = getSourceToTargetKeyFields().keySet().iterator();
        Iterator targetFieldEnum = getSourceToTargetKeyFields().values().iterator();
        while (sourceFieldEnum.hasNext()) {
            Object fieldValue = ((DatabaseField)sourceFieldEnum.next()).getQualifiedName();
            Object attributeValue = ((DatabaseField)targetFieldEnum.next()).getQualifiedName();
            associations.addElement(new Association(fieldValue, attributeValue));
        }

        return associations;
    }

    /**
     * INTERNAL:
     * Returns the source keys to target keys fields association.
     */
    public Map<DatabaseField, DatabaseField> getSourceToTargetKeyFields() {
        return sourceToTargetKeyFields;
    }
    
    /**
     * INTERNAL:
     * Returns the target keys to source keys fields association.
     */
    public Map<DatabaseField, DatabaseField> getTargetToSourceKeyFields() {
        return targetToSourceKeyFields;
    }

    /**
     * INTERNAL:
     * If required, get the targetVersion of the source object from the merge manager
     * 
     * Used with MapKeyContainerPolicy to abstract getting the target version of a source key
     * @return
     */
    public Object getTargetVersionOfSourceObject(Object object, Object parent, MergeManager mergeManager){
       return  mergeManager.getTargetVersionOfSourceObject(object);
    }
    
    /**
     * INTERNAL:
     * Initialize the mapping.
     */
    public void initialize(AbstractSession session) throws DescriptorException {
        super.initialize(session);

        // Must set table of foreign keys.
        for (int index = 0; index < getForeignKeyFields().size(); index++) {
            DatabaseField foreignKeyField = getForeignKeyFields().get(index);
            foreignKeyField = getDescriptor().buildField(foreignKeyField, keyTableForMapKey);
            getForeignKeyFields().set(index, foreignKeyField);
        }

        // If only a selection criteria is specified then the foreign keys do not have to be initialized.
        if (!(getTargetToSourceKeyFields().isEmpty() && getSourceToTargetKeyFields().isEmpty())) {
            if (getTargetToSourceKeyFields().isEmpty() || getSourceToTargetKeyFields().isEmpty()) {
                initializeForeignKeysWithDefaults(session);
            } else {
                initializeForeignKeys(session);
            }
        }

        if (shouldInitializeSelectionCriteria()) {
            if (shouldForceInitializationOfSelectionCriteria()) {
                setSelectionCriteria(buildSelectionCriteria());
            } else {
                buildSelectionCriteria(true, true);
            }
        } else {
            setShouldVerifyDelete(false);
        }

        setFields(collectFields());
        
        if (getReferenceDescriptor() != null && getReferenceDescriptor().hasTablePerClassPolicy()) {
            // This will do nothing if we have already prepared for this 
            // source mapping or if the source mapping does not require
            // any special prepare logic.
            getReferenceDescriptor().getTablePerClassPolicy().prepareChildrenSelectionQuery(this, session);              
        }
    }

    /**
     * INTERNAL:
     * The foreign keys primary keys are stored as database fields in the hashtable.
     */
    protected void initializeForeignKeys(AbstractSession session) {
        HashMap<DatabaseField, DatabaseField> newSourceToTargetKeyFields = new HashMap(getSourceToTargetKeyFields().size());
        HashMap<DatabaseField, DatabaseField> newTargetToSourceKeyFields = new HashMap(getTargetToSourceKeyFields().size());
        Iterator<Map.Entry<DatabaseField, DatabaseField>> iterator = getSourceToTargetKeyFields().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DatabaseField, DatabaseField> entry = (Map.Entry)iterator.next();
            DatabaseField sourceField = entry.getKey();
            sourceField = getDescriptor().buildField(sourceField, keyTableForMapKey);
            DatabaseField targetField = entry.getValue();
            targetField = getReferenceDescriptor().buildField(targetField, keyTableForMapKey);
            newSourceToTargetKeyFields.put(sourceField, targetField);
            newTargetToSourceKeyFields.put(targetField, sourceField);
        }
        setSourceToTargetKeyFields(newSourceToTargetKeyFields);
        setTargetToSourceKeyFields(newTargetToSourceKeyFields);
    }

    /**
     * INTERNAL:
     * The foreign keys primary keys are stored as database fields in the hashtable.
     */
    protected void initializeForeignKeysWithDefaults(AbstractSession session) {
        if (isForeignKeyRelationship()) {
            if (getSourceToTargetKeyFields().size() != 1) {
                throw DescriptorException.foreignKeysDefinedIncorrectly(this);
            }
            List<DatabaseField> targetKeys = getReferenceDescriptor().getPrimaryKeyFields();
            if (targetKeys.size() != 1) {
                //target and source keys are not the same size.
                throw DescriptorException.sizeMismatchOfForeignKeys(this);
            }

            //grab the only element out of the Hashtable
            DatabaseField sourceField = getSourceToTargetKeyFields().keySet().iterator().next();
            sourceField = getDescriptor().buildField(sourceField);
            getSourceToTargetKeyFields().clear();
            getTargetToSourceKeyFields().clear();
            getSourceToTargetKeyFields().put(sourceField, targetKeys.get(0));
            getTargetToSourceKeyFields().put(targetKeys.get(0), sourceField);
        } else {
            if (getTargetToSourceKeyFields().size() != 1) {
                throw DescriptorException.foreignKeysDefinedIncorrectly(this);
            }
            List<DatabaseField> sourceKeys = getDescriptor().getPrimaryKeyFields();
            if (sourceKeys.size() != 1) {
                //target and source keys are not the same size.
                throw DescriptorException.sizeMismatchOfForeignKeys(this);
            }

            //grab the only element out of the Hashtable
            DatabaseField targetField = getTargetToSourceKeyFields().keySet().iterator().next();
            targetField = getReferenceDescriptor().buildField(targetField);
            getSourceToTargetKeyFields().clear();
            getTargetToSourceKeyFields().clear();
            getTargetToSourceKeyFields().put(targetField, sourceKeys.get(0));
            getSourceToTargetKeyFields().put(sourceKeys.get(0), targetField);
        }
    }

    /**
     * INTERNAL:
     * Selection criteria is created with source foreign keys and target keys.
     */
    protected void initializePrivateOwnedCriteria() {
        if (!isForeignKeyRelationship()) {
            setPrivateOwnedCriteria(getSelectionCriteria());
        } else {
            Expression pkCriteria = getDescriptor().getObjectBuilder().getPrimaryKeyExpression();
            ExpressionBuilder builder = new ExpressionBuilder();
            Expression backRef = builder.getManualQueryKey(getAttributeName() + "-back-ref", getDescriptor());
            Expression newPKCriteria = pkCriteria.rebuildOn(backRef);
            Expression twistedSelection = backRef.twist(getSelectionCriteria(), builder);
            if (getDescriptor().getQueryManager().getAdditionalJoinExpression() != null) {
                // We don't have to twist the additional join because it's all against the same node, which is our base
                // but we do have to rebuild it onto the manual query key
                Expression rebuiltAdditional = getDescriptor().getQueryManager().getAdditionalJoinExpression().rebuildOn(backRef);
                if (twistedSelection == null) {
                    twistedSelection = rebuiltAdditional;
                } else {
                    twistedSelection = twistedSelection.and(rebuiltAdditional);
                }
            }
            setPrivateOwnedCriteria(newPKCriteria.and(twistedSelection));
        }
    }

    /**
     * INTERNAL:
     * Making any mapping changes necessary to use a the mapping as a map key prior to initializing the mapping
     */
    public void preinitializeMapKey(DatabaseTable table) throws DescriptorException {
        keyTableForMapKey = table;
    }

    /**
     * INTERNAL:
     * Allow the selectionQuery to be modified when this MapComponentMapping is used as the value in a Map
     */
    public void postInitializeMapValueSelectionQuery(ReadQuery selectionQuery, AbstractSession session){
    }
    
    /**
     * INTERNAL:
     * Prepare a cascade locking policy.
     */
    public void prepareCascadeLockingPolicy() {
        CascadeLockingPolicy policy = new CascadeLockingPolicy(getDescriptor(), getReferenceDescriptor());
        policy.setQueryKeyFields(getSourceToTargetKeyFields(), ! isForeignKeyRelationship());
        getReferenceDescriptor().addCascadeLockingPolicy(policy);
    }
    
    /**
     * This method would allow customers to get the potential selection criteria for a mapping
     * prior to initialization.  This would allow them to more easily create an ammendment method
     * that would ammend the SQL for the join.
     */
    public Expression buildSelectionCriteria() {
        return buildSelectionCriteria(true, false);
    }
    
    /**
     * INTERNAL:
     * Build the selection criteria for this mapping.  Allows several variations.
     * 
     * Either a parameter can be used for the join or simply the database field
     * 
     * The existing selection criteria can be built upon or a whole new criteria can be built.
     * @param useParameter
     * @param usePreviousSelectionCriteria
     * @return
     */
    public Expression buildSelectionCriteria(boolean useParameter, boolean usePreviousSelectionCriteria){
        // CR3922
        if (getSourceToTargetKeyFields().isEmpty()) {
            throw DescriptorException.noForeignKeysAreSpecified(this);
        }

        Expression criteria = null;
        Expression builder = new ExpressionBuilder();

        for (Iterator keys = getSourceToTargetKeyFields().keySet().iterator(); keys.hasNext();) {
            DatabaseField foreignKey = (DatabaseField)keys.next();
            DatabaseField targetKey = getSourceToTargetKeyFields().get(foreignKey);

            Expression expression = null;
            if (useParameter){
                expression = builder.getField(targetKey).equal(builder.getParameter(foreignKey));
            } else {
                expression = builder.getField(targetKey).equal(builder.getField(foreignKey));
            }
            
            if (usePreviousSelectionCriteria){
                setSelectionCriteria(expression.and(getSelectionCriteria()));
            } else {
                if (criteria == null) {
                    criteria = expression;
                } else {
                    criteria = expression.and(criteria);
                }
            }
        }
        return criteria;
    }

    /**
     * INTERNAL:
     * Builds a shallow original object.  Only direct attributes and primary
     * keys are populated.  In this way the minimum original required for
     * instantiating a working copy clone can be built without placing it in
     * the shared cache (no concern over cycles).
     */
    public void buildShallowOriginalFromRow(AbstractRecord databaseRow, Object original, JoinedAttributeManager joinManager, ObjectBuildingQuery query, AbstractSession executionSession) {
        // Now we are only building this original so we can extract the primary
        // key out of it.  If the primary key is stored accross a 1-1 a value
        // holder needs to be built/triggered to get at it.
        // In this case recursively build the shallow original accross the 1-1.
        // We only need the primary key for that object, and we know
        // what that primary key is: it is the foreign key in our row.
        ClassDescriptor descriptor = getReferenceDescriptor();
        AbstractRecord targetRow = new DatabaseRecord();

        for (Iterator keys = getSourceToTargetKeyFields().keySet().iterator(); keys.hasNext();) {
            DatabaseField foreignKey = (DatabaseField)keys.next();
            DatabaseField targetKey = getSourceToTargetKeyFields().get(foreignKey);

            targetRow.put(targetKey, databaseRow.get(foreignKey));
        }

        Object targetObject = descriptor.getObjectBuilder().buildNewInstance();
        descriptor.getObjectBuilder().buildAttributesIntoShallowObject(targetObject, databaseRow, query);
        targetObject = getIndirectionPolicy().valueFromRow(targetObject);

        setAttributeValueInObject(original, targetObject);
    }

    /**
     * INTERNAL:
     */
    public boolean isOneToOneMapping() {
        return true;
    }

    /**
     * INTERNAL:
     * Reads the private owned object.
     */
    protected Object readPrivateOwnedForObject(ObjectLevelModifyQuery modifyQuery) throws DatabaseException {
        if (modifyQuery.getSession().isUnitOfWork()) {
            return super.readPrivateOwnedForObject(modifyQuery);
        } else {
            if (!shouldVerifyDelete()) {
                return null;
            }
            ReadObjectQuery readQuery = (ReadObjectQuery)getSelectionQuery().clone();

            readQuery.setSelectionCriteria(getPrivateOwnedCriteria());
            return modifyQuery.getSession().executeQuery(readQuery, modifyQuery.getTranslationRow());
        }
    }

    /**
     * INTERNAL:
     * Rehash any hashtables based on fields.
     * This is used to clone descriptors for aggregates, which hammer field names,
     * it is probably better not to hammer the field name and this should be refactored.
     */
    public void rehashFieldDependancies(AbstractSession session) {
        setSourceToTargetKeyFields(Helper.rehashMap(getSourceToTargetKeyFields()));
    }

    /**
     * PUBLIC:
     * Define the foreign key relationship in the 1-1 mapping.
     * This method is used for singleton foreign key relationships only,
     * that is the source object's table has a foreign key field to
     * the target object's primary key field.
     * Only the source foreign key field name is specified.
     * When a foreign key is specified TopLink will automatically populate the value
     * for that field from the target object when the object is written to the database.
     * If the foreign key is also mapped through a direct-to-field then the direct-to-field must
     * be set read-only.
     */
    public void setForeignKeyFieldName(String sourceForeignKeyFieldName) {
        DatabaseField sourceField = new DatabaseField(sourceForeignKeyFieldName);

        setIsForeignKeyRelationship(true);
        getForeignKeyFields().addElement(sourceField);
        getSourceToTargetKeyFields().put(sourceField, new DatabaseField());
    }

    /**
     * PUBLIC:
     * Return the foreign key field names associated with the mapping.
     * These are only the source fields that are writable.
     */
    public void setForeignKeyFieldNames(Vector fieldNames) {
        Vector fields = org.eclipse.persistence.internal.helper.NonSynchronizedVector.newInstance(fieldNames.size());
        for (Enumeration fieldNamesEnum = fieldNames.elements(); fieldNamesEnum.hasMoreElements();) {
            fields.addElement(new DatabaseField((String)fieldNamesEnum.nextElement()));
        }

        setForeignKeyFields(fields);
    }

    /**
     * INTERNAL:
     * Private owned criteria is used to verify the deletion of the target.
     * It joins from the source table on the foreign key to the target table,
     * with a parameterization of the primary key of the source object.
     */
    protected void setPrivateOwnedCriteria(Expression expression) {
        privateOwnedCriteria = expression;
    }

    /**
     * PUBLIC:
     * Verify delete is used during delete and update on private 1:1's outside of a unit of work only.
     * It checks for the previous value of the target object through joining the source and target tables.
     * By default it is always done, but may be disabled for performance on distributed database reasons.
     * In the unit of work the previous value is obtained from the backup-clone so it is never used.
     */
    public void setShouldVerifyDelete(boolean shouldVerifyDelete) {
        this.shouldVerifyDelete = shouldVerifyDelete;
    }

    /**
     * INTERNAL:
     * Set a collection of the source to target field associations.
     */
    public void setSourceToTargetKeyFieldAssociations(Vector sourceToTargetKeyFieldAssociations) {
        setSourceToTargetKeyFields(new HashMap(sourceToTargetKeyFieldAssociations.size() + 1));
        setTargetToSourceKeyFields(new HashMap(sourceToTargetKeyFieldAssociations.size() + 1));
        for (Enumeration associationsEnum = sourceToTargetKeyFieldAssociations.elements();
                 associationsEnum.hasMoreElements();) {
            Association association = (Association)associationsEnum.nextElement();
            DatabaseField sourceField = new DatabaseField((String)association.getKey());
            DatabaseField targetField = new DatabaseField((String)association.getValue());
            getSourceToTargetKeyFields().put(sourceField, targetField);
            getTargetToSourceKeyFields().put(targetField, sourceField);
        }
    }

    /**
     * INTERNAL:
     * Set the source keys to target keys fields association.
     */
    public void setSourceToTargetKeyFields(Map<DatabaseField, DatabaseField> sourceToTargetKeyFields) {
        this.sourceToTargetKeyFields = sourceToTargetKeyFields;
    }

    /**
     * PUBLIC:
     * Define the target foreign key relationship in the 1-1 mapping.
     * This method is used for singleton target foreign key relationships only,
     * that is the target object's table has a foreign key field to
     * the source object's primary key field.
     * The target foreign key field name is specified.
     * The distinction between a foreign key and target foreign key is that the 1-1
     * mapping will not populate the target foreign key value when written (because it is in the target table).
     * Normally 1-1's are through foreign keys but in bi-directional 1-1's
     * the back reference will be a target foreign key.
     */
    public void setTargetForeignKeyFieldName(String targetForeignKeyFieldName) {
        DatabaseField targetField = new DatabaseField(targetForeignKeyFieldName);
        getTargetToSourceKeyFields().put(targetField, new DatabaseField());
    }

    /**
     * INTERNAL:
     * Set the target keys to source keys fields association.
     */
    public void setTargetToSourceKeyFields(Map<DatabaseField, DatabaseField> targetToSourceKeyFields) {
        this.targetToSourceKeyFields = targetToSourceKeyFields;
    }


    /**
     * PUBLIC:
     * Verify delete is used during delete and update outside of a unit of work only.
     * It checks for the previous value of the target object through joining the source and target tables.
     */
    public boolean shouldVerifyDelete() {
        return shouldVerifyDelete;
    }

    /**
     * INTERNAL
     * Return true if this mapping supports cascaded version optimistic locking.
     */
    public boolean isCascadedLockingSupported() {
        return true;
    }
    
    /**
     * INTERNAL:
     * Return if this mapping support joining.
     */
    public boolean isJoiningSupported() {
        return true;
    }
    
    /**
     * INTERNAL:
     * Called when iterating through descriptors to handle iteration on this mapping when it is used as a MapKey
     * @param iterator
     * @param element
     */
    public void iterateOnMapKey(DescriptorIterator iterator, Object element){
        this.getIndirectionPolicy().iterateOnAttributeValue(iterator, element);
    }

    /**
     * INTERNAL:
     * Allow the key mapping to unwrap the object
     * @param key
     * @param session
     * @return
     */
    
    public Object unwrapKey(Object key, AbstractSession session){
        return getDescriptor().getObjectBuilder().unwrapObject(key, session);
    }

    /**
     * INTERNAL:
     * Allow the key mapping to wrap the object
     * @param key
     * @param session
     * @return
     */
    
    public Object wrapKey(Object key, AbstractSession session){
        return getDescriptor().getObjectBuilder().wrapObject(key, session);
    }
    
    /**
     * INTERNAL:
     * A subclass should implement this method if it wants different behavior.
     * Write the foreign key values from the attribute to the row.
     */
    public void writeFromAttributeIntoRow(Object attribute, AbstractRecord row, AbstractSession session)
    {
          for (Enumeration fieldsEnum = getForeignKeyFields().elements(); fieldsEnum.hasMoreElements();) {
                  DatabaseField sourceKey = (DatabaseField) fieldsEnum.nextElement();
                  DatabaseField targetKey = getSourceToTargetKeyFields().get(sourceKey);
                  Object referenceValue = null;
                          // If privately owned part is null then method cannot be invoked.
                  if (attribute != null) {
                          referenceValue = getReferenceDescriptor().getObjectBuilder().extractValueFromObjectForField(attribute, targetKey, session);
                  }
                  row.add(sourceKey, referenceValue);
          }
    }

    /**
     * INTERNAL:
     * Get a value from the object and set that in the respective field of the row.
     */
    public Object valueFromObject(Object object, DatabaseField field, AbstractSession session) {
        // First check if the value can be obtained from the value holder's row.
        Object attributeValue = getAttributeValueFromObject(object);
        AbstractRecord referenceRow = this.indirectionPolicy.extractReferenceRow(attributeValue);
        if (referenceRow != null) {
            Object value = referenceRow.get(field);
            Class type = getFieldClassification(field);
            if ((value == null) || (value.getClass() != type)) {
                // Must ensure the classification to get a cache hit.
                try {
                    value = session.getDatasourcePlatform().convertObject(value, type);
                } catch (ConversionException exception) {
                    throw ConversionException.couldNotBeConverted(this, getDescriptor(), exception);
                }
            }
            return value;
        }

        Object referenceObject = getRealAttributeValueFromAttribute(attributeValue, object, session);
        if (referenceObject == null) {
            return null;
        }
        DatabaseField targetField = this.sourceToTargetKeyFields.get(field);

        return this.referenceDescriptor.getObjectBuilder().extractValueFromObjectForField(referenceObject, targetField, session);
    }

    /**
     * INTERNAL:
     * Return the value of the field from the row or a value holder on the query to obtain the object.
     * Check for batch + aggregation reading.
     */
    protected Object valueFromRowInternalWithJoin(AbstractRecord row, JoinedAttributeManager joinManager, ObjectBuildingQuery sourceQuery, AbstractSession executionSession) throws DatabaseException {
        // PERF: Direct variable access.
        Object referenceObject;
        // CR #... the field for many objects may be in the row,
        // so build the subpartion of the row through the computed values in the query,
        // this also helps the field indexing match.
        AbstractRecord targetRow = trimRowForJoin(row, joinManager, executionSession);
        // PERF: Only check for null row if an outer-join was used.
        if (((joinManager != null) && joinManager.hasOuterJoinedAttributeQuery()) && !sourceQuery.hasPartialAttributeExpressions()) {
            Vector key = this.referenceDescriptor.getObjectBuilder().extractPrimaryKeyFromRow(targetRow, executionSession);
            if (key == null) {
                return this.indirectionPolicy.nullValueFromRow();
            }
        }
        // A nested query must be built to pass to the descriptor that looks like the real query execution would,
        // these should be cached on the query during prepare.
        ObjectLevelReadQuery nestedQuery = prepareNestedJoinQueryClone(row, null, joinManager, sourceQuery, executionSession);
        nestedQuery.setTranslationRow(targetRow);
        referenceObject = this.referenceDescriptor.getObjectBuilder().buildObject(nestedQuery, targetRow);

        // For bug 3641713 buildObject doesn't wrap if called on a UnitOfWork for performance reasons,
        // must wrap here as this is the last time we can look at the query and tell whether to wrap or not.
        if (nestedQuery.shouldUseWrapperPolicy() && executionSession.isUnitOfWork()) {
            referenceObject = this.referenceDescriptor.getObjectBuilder().wrapObject(referenceObject, executionSession);
        }
        return this.indirectionPolicy.valueFromRow(referenceObject);
    }
    
    /**
     * INTERNAL:
     * Return the value of the field from the row or a value holder on the query to obtain the object.
     * Check for batch + aggregation reading.
     */
    protected Object valueFromRowInternal(AbstractRecord row, JoinedAttributeManager joinManager, ObjectBuildingQuery sourceQuery, AbstractSession executionSession) throws DatabaseException {
        // If any field in the foreign key is null then it means there are no referenced objects
        // Skip for partial objects as fk may not be present.
        int size = this.fields.size();
        for (int index = 0; index < size; index++) {
            DatabaseField field = this.fields.get(index);
            if (row.get(field) == null) {
                return this.indirectionPolicy.nullValueFromRow();
            }
        }

        // Call the default which executes the selection query,
        // or wraps the query with a value holder.
        return super.valueFromRowInternal(row, joinManager, sourceQuery, executionSession);
    }

    /**
     * INTERNAL:
     * Get a value from the object and set that in the respective field of the row.
     */
    public void writeFromObjectIntoRow(Object object, AbstractRecord databaseRow, AbstractSession session) {
        if (this.isReadOnly || (!this.isForeignKeyRelationship)) {
            return;
        }
        Object attributeValue = getAttributeValueFromObject(object);
        // If the value holder has the row, avoid instantiation and just use it.
        AbstractRecord referenceRow = this.indirectionPolicy.extractReferenceRow(attributeValue);
        if (referenceRow == null) {
            // Extract from object.
            Object referenceObject = getRealAttributeValueFromAttribute(attributeValue, object, session);
            List<DatabaseField> foreignKeyFields = getForeignKeyFields();
            int size = foreignKeyFields.size();
            for (int index = 0; index < size; index++) {
                DatabaseField sourceKey = foreignKeyFields.get(index);
                Object referenceValue = null;
                // If privately owned part is null then method cannot be invoked.
                if (referenceObject != null) {
                    DatabaseField targetKey = this.sourceToTargetKeyFields.get(sourceKey);
                    referenceValue = this.referenceDescriptor.getObjectBuilder().extractValueFromObjectForField(referenceObject, targetKey, session);
                }
                databaseRow.add(sourceKey, referenceValue);
            }
        } else {
            List<DatabaseField> foreignKeyFields = getForeignKeyFields();
            int size = foreignKeyFields.size();
            for (int index = 0; index < size; index++) {
                DatabaseField sourceKey = foreignKeyFields.get(index);
                databaseRow.add(sourceKey, referenceRow.get(sourceKey));
            }
        }
    }

    /**
     * INTERNAL:
     * This row is built for shallow insert which happens in case of bidirectional inserts.
     * The foreign keys must be set to null to avoid constraints.
     */
    public void writeFromObjectIntoRowForShallowInsert(Object object, AbstractRecord databaseRow, AbstractSession session) {
        if (isReadOnly() || (!isForeignKeyRelationship())) {
            return;
        }

        for (Enumeration fieldsEnum = getForeignKeyFields().elements();
                 fieldsEnum.hasMoreElements();) {
            DatabaseField sourceKey = (DatabaseField)fieldsEnum.nextElement();
            databaseRow.add(sourceKey, null);
        }
    }

    /**
     * INTERNAL:
     * Get a value from the object and set that in the respective field of the row.
     * Validation preventing primary key updates is implemented here.
     */
    public void writeFromObjectIntoRowWithChangeRecord(ChangeRecord changeRecord, AbstractRecord databaseRow, AbstractSession session) {
        if ((!this.isReadOnly) && this.isPrimaryKeyMapping && (!changeRecord.getOwner().isNew())) {
           throw ValidationException.primaryKeyUpdateDisallowed(changeRecord.getOwner().getClassName(), changeRecord.getAttribute());
        }
        
        // The object must be used here as the foreign key may include more than just the
        // primary key of the referenced object and the changeSet may not have the required information.
        Object object = ((ObjectChangeSet)changeRecord.getOwner()).getUnitOfWorkClone();
        writeFromObjectIntoRow(object, databaseRow, session);
    }

    /**
     * INTERNAL:
     * This row is built for shallow insert which happens in case of bidirectional inserts.
     * The foreign keys must be set to null to avoid constraints.
     */
    public void writeFromObjectIntoRowForShallowInsertWithChangeRecord(ChangeRecord ChangeRecord, AbstractRecord databaseRow, AbstractSession session) {
        if (isReadOnly() || (!isForeignKeyRelationship())) {
            return;
        }

        for (Enumeration fieldsEnum = getForeignKeyFields().elements();
                 fieldsEnum.hasMoreElements();) {
            DatabaseField sourceKey = (DatabaseField)fieldsEnum.nextElement();
            databaseRow.add(sourceKey, null);
        }
    }

    /**
     * INTERNAL:
     * Write fields needed for insert into the template for with null values.
     */
    public void writeInsertFieldsIntoRow(AbstractRecord databaseRow, AbstractSession session) {
        if (isReadOnly() || (!isForeignKeyRelationship())) {
            return;
        }

        for (Enumeration fieldsEnum = getForeignKeyFields().elements();
                 fieldsEnum.hasMoreElements();) {
            DatabaseField sourceKey = (DatabaseField)fieldsEnum.nextElement();
            databaseRow.add(sourceKey, null);
        }
    }
}
