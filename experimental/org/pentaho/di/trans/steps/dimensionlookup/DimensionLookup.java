 /**********************************************************************
 **                                                                   **
 **               This code belongs to the KETTLE project.            **
 **                                                                   **
 ** Kettle, from version 2.2 on, is released into the public domain   **
 ** under the Lesser GNU Public License (LGPL).                       **
 **                                                                   **
 ** For more details, please read the document LICENSE.txt, included  **
 ** in this project                                                   **
 **                                                                   **
 ** http://www.kettle.be                                              **
 ** info@kettle.be                                                    **
 **                                                                   **
 **********************************************************************/

package org.pentaho.di.trans.steps.dimensionlookup;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.hash.ByteArrayHashIndex;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import be.ibridge.kettle.core.Const;
import be.ibridge.kettle.core.exception.KettleDatabaseException;
import be.ibridge.kettle.core.exception.KettleException;
import be.ibridge.kettle.core.exception.KettleStepException;
import be.ibridge.kettle.core.exception.KettleValueException;





/**
 * Manages a slowly chaning dimension (lookup or update)
 * 
 * @author Matt
 * @since 14-may-2003
 *
 */

public class DimensionLookup extends BaseStep implements StepInterface
{
	private final static int CREATION_METHOD_AUTOINC  = 1;
    private final static int CREATION_METHOD_SEQUENCE = 2;
	private final static int CREATION_METHOD_TABLEMAX = 3;
	
	private int techKeyCreation;	
	
	private DimensionLookupMeta meta;	
	private DimensionLookupData data;
	
	public DimensionLookup(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta, Trans trans)
	{
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
	}
	
	private void setTechKeyCreation(int method)
	{
		techKeyCreation = method;
	}

	private int getTechKeyCreation()
	{
		return techKeyCreation;
	}
	
	private void determineTechKeyCreation()
	{
		String keyCreation = meta.getTechKeyCreation();
		if (meta.getDatabaseMeta().supportsAutoinc() && 
			DimensionLookupMeta.CREATION_METHOD_AUTOINC.equals(keyCreation) )
		{
		    setTechKeyCreation(CREATION_METHOD_AUTOINC);
		}
		else if (meta.getDatabaseMeta().supportsSequences() && 
		  	     DimensionLookupMeta.CREATION_METHOD_SEQUENCE.equals(keyCreation) )
		{
		    setTechKeyCreation(CREATION_METHOD_SEQUENCE);
		}
		else
		{
			setTechKeyCreation(CREATION_METHOD_TABLEMAX);
		}		
	}	
	
    public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException
    {
        meta=(DimensionLookupMeta)smi;
        data=(DimensionLookupData)sdi;

        Object[] r=getRow();       // Get row from input rowset & set row busy!
        if (r==null)  // no more input to be expected...
        {
            setOutputDone();  // signal end to receiver(s)
            return false;
        }

        if (first)
        {
            first=false;
            
            data.schemaTable = meta.getDatabaseMeta().getQuotedSchemaTableCombination(meta.getSchemaName(), meta.getTableName());
            
            data.outputRowMeta = (RowMetaInterface) getInputRowMeta().clone();
            meta.getFields(data.outputRowMeta, getStepname(), null);
                        
            // Lookup values
            data.keynrs = new int[meta.getKeyStream().length];
            for (int i=0;i<meta.getKeyStream().length;i++)
            {
                //logDetailed("Lookup values key["+i+"] --> "+key[i]+", row==null?"+(row==null));
                data.keynrs[i]=getInputRowMeta().indexOfValue(meta.getKeyStream()[i]);
                if (data.keynrs[i]<0) // couldn't find field!
                {
                    throw new KettleStepException(Messages.getString("DimensionLookup.Exception.KeyFieldNotFound",meta.getKeyStream()[i])); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // Return values
            if (meta.isUpdate())
            {
                data.fieldnrs = new int[meta.getFieldStream().length];
                for (int i=0;meta.getFieldStream()!=null && i<meta.getFieldStream().length;i++)
                {
                    data.fieldnrs[i]=getInputRowMeta().indexOfValue(meta.getFieldStream()[i]);
                }
            }

            if (meta.getDateField()!=null && meta.getDateField().length()>0)
            { 
                data.datefieldnr = getInputRowMeta().indexOfValue(meta.getDateField());
            }
            else 
            {
                data.datefieldnr=-1;
            } 

            data.notFoundTk = new Long( (long)meta.getDatabaseMeta().getNotFoundTK(isAutoIncrement()) );
            // if (meta.getKeyRename()!=null && meta.getKeyRename().length()>0) data.notFoundTk.setName(meta.getKeyRename());

            if (meta.getDateField()!=null && data.datefieldnr>=0)
            {
                data.valueDateNow = getInputRowMeta().getDate(r, data.datefieldnr);
            }
            else
            {
                data.valueDateNow = new Date(System.currentTimeMillis()); // System date... //$NON-NLS-1$
            }
            
            // Caching...
            if (meta.getCacheSize()>=0)
            {
                // KEY : the natural key(s)
                //
                data.cacheKeyRowMeta = new RowMeta();
                for (int i=0;i<data.keynrs.length;i++)
                {
                    ValueMetaInterface key = getInputRowMeta().getValueMeta(data.keynrs[i]);
                    data.cacheKeyRowMeta.addValueMeta((ValueMetaInterface) key.clone());
                }
                
                data.cache = new ByteArrayHashIndex(meta.getCacheSize()>0 ? meta.getCacheSize() : 5000, data.cacheKeyRowMeta);
            
                // VALUE : tk, version, fields, from date, to date 
                //
                data.cacheValueRowMeta = new RowMeta();
                data.cacheValueRowMeta.addValueMeta( new ValueMeta(meta.getKeyField(), ValueMetaInterface.TYPE_INTEGER) );
                data.cacheValueRowMeta.addValueMeta( new ValueMeta(meta.getVersionField(), ValueMetaInterface.TYPE_INTEGER) );
                for (int i=0;i<data.fieldnrs.length;i++)
                {
                    ValueMetaInterface v = getInputRowMeta().getValueMeta(data.fieldnrs[i]);
                    data.cacheValueRowMeta.addValueMeta(v);
                }
                data.cacheValueRowMeta.addValueMeta( new ValueMeta(meta.getDateFrom(), ValueMetaInterface.TYPE_DATE) );
                data.cacheValueRowMeta.addValueMeta( new ValueMeta(meta.getDateTo(), ValueMetaInterface.TYPE_DATE) );
            }
            
            determineTechKeyCreation();
            if (getCopy()==0) checkDimZero();
            
            setDimLookup(getInputRowMeta());
        }
        
        try
        {
            Object[] outputRow = lookupValues(getInputRowMeta(), r); // add new values to the row in rowset[0].
            putRow(data.outputRowMeta, outputRow);       // copy row to output rowset(s);
            
            if (checkFeedback(linesRead)) logBasic(Messages.getString("DimensionLookup.Log.LineNumber")+linesRead); //$NON-NLS-1$
        }
        catch(KettleException e)
        {
            logError(Messages.getString("DimensionLookup.Log.StepCanNotContinueForErrors", e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
            logError(Const.getStackTracker(e)); //$NON-NLS-1$ //$NON-NLS-2$
            setErrors(1);
            stopAll();
            setOutputDone();  // signal end to receiver(s)
            return false;
        }
    
        return true;
    }
    
    
	private synchronized Object[] lookupValues(RowMetaInterface rowMeta, Object[] row) throws KettleException
	{
        Object[] outputRow = new Object[data.outputRowMeta.size()];
        
        Object[] lookupRow = new Object[data.lookupRowMeta.size()];
		Object[] returnRow = null;
        
		Long technicalKey;
		Long valueVersion;
		Date valueDate    = null;
		Date valueDateFrom = null;
		Date valueDateTo   = null;

        // Determine "Now" once, the first time we get here...
		if (data.valueDateNow==null && meta.getDateField()!=null && data.datefieldnr>=0)
		{
			data.valueDateNow = rowMeta.getDate(row, data.datefieldnr);
		}
		
        // Construct the 
		for (int i=0;i<meta.getKeyStream().length;i++)
		{
			try
			{
				lookupRow[i] = row[data.keynrs[i]];
			}
			catch(Exception e) // TODO : remove exception??
			{
				throw new KettleStepException(Messages.getString("DimensionLookup.Exception.ErrorDetectedInGettingKey",i+"",data.keynrs[i]+"/"+rowMeta.size(),row.toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
		}
		if (data.datefieldnr>=0) valueDate = rowMeta.getDate(row, data.datefieldnr);
		else valueDate = data.valueDateNow;
        lookupRow[meta.getKeyStream().length]=valueDate;  // ? >= date_from
        lookupRow[meta.getKeyStream().length+1]=valueDate; // ? < date_to
		
		if (log.isDebug()) logDebug(Messages.getString("DimensionLookup.Log.LookupRow")+data.lookupRowMeta.getString(lookupRow)); //$NON-NLS-1$ //$NON-NLS-2$
		
        // Do the lookup and see if we can find anything in the database.
        // But before that, let's see if we can find anything in the cache
        //
        
		if (meta.getCacheSize()>=0)
        {
            returnRow=getFromCache(lookupRow, valueDate);
        }
        
        if (returnRow==null)
        {
            data.db.setValues(data.lookupRowMeta, lookupRow, data.prepStatementLookup);
            returnRow=data.db.getLookup(data.prepStatementLookup);
            
            linesInput++;
            
            if (returnRow!=null && meta.getCacheSize()>=0)
            {
                addToCache(lookupRow, returnRow);
            }
        }
		
		/* Handle "update = false" first for performance reasons
		 */
		if (!meta.isUpdate())
		{
			if (returnRow==null)
			{
                returnRow=new Object[data.returnRowMeta.size()];
                returnRow[0] = data.notFoundTk; 

                if (meta.getCacheSize()>=0) // need -oo to +oo as well...
                {
                    returnRow[returnRow.length-2] = data.min_date;
                    returnRow[returnRow.length-1] = data.max_date;
                }
			}
			else
			{
				// We found the return values in row "add".
				// Throw away the version nr...
				// add.removeValue(1);
				
				// Rename the key field if needed.  Do it directly in the row...
				// if (meta.getKeyRename()!=null && meta.getKeyRename().length()>0) add.getValue(0).setName(meta.getKeyRename());
			}
		}
		else  // Insert - update algorithm for slowly changing dimensions
		{
			if (returnRow==null) // The dimension entry was not found, we need to add it!
			{
				if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.NoDimensionEntryFound")+data.lookupRowMeta.getString(lookupRow)+")"); //$NON-NLS-1$ //$NON-NLS-2$
				//logDetailed("Entry not found: add value!");
				// Date range: ]-oo,+oo[ 
				valueDateFrom = data.min_date;
				valueDateTo   = data.max_date;
				valueVersion  = new Long(1L);     // Versions always start at 1.
				
				// get a new value from the sequence choosen.
				technicalKey = null;
				switch ( getTechKeyCreation() )
				{
				    case CREATION_METHOD_TABLEMAX:
						// What's the next value for the technical key?
						technicalKey= new Long(0L); // value to accept new key...
						data.db.getNextValue(getTransMeta().getCounters(), meta.getSchemaName(), meta.getTableName(), meta.getKeyField());
                        break;
				    case CREATION_METHOD_AUTOINC:
						technicalKey=new Long(0L); // value to accept new key...
						break;
				    case CREATION_METHOD_SEQUENCE:						
						technicalKey=data.db.getNextSequenceValue(meta.getSchemaName(), meta.getSequenceName(), meta.getKeyField());
						if (technicalKey!=null && log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.FoundNextSequence")+technicalKey.toString()); //$NON-NLS-1$
						break;					
				}	               

				/*
				 *   INSERT INTO table(version, datefrom, dateto, fieldlookup)
				 *   VALUES(valueVersion, valueDateFrom, valueDateTo, row.fieldnrs)
				 *   ;
				 */
				
				technicalKey = dimInsert(getInputRowMeta(), row, technicalKey, true, valueVersion, valueDateFrom, valueDateTo); 
								
				linesOutput++;
				returnRow = new Object[data.returnRowMeta.size()];
                int returnIndex=0;
                
                returnRow[returnIndex] = technicalKey;
                returnIndex++;
                
                // See if we need to store this record in the cache as well...
                if (meta.getCacheSize()>=0)
                {
                    Object[] values = getCacheValues(rowMeta, row, technicalKey, valueVersion, valueDateFrom, valueDateTo);
                    
                    // put it in the cache...
                    addToCache(lookupRow, values);
                }
                
				if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.AddedDimensionEntry")+data.returnRowMeta.getString(returnRow)); //$NON-NLS-1$
			}
			else  // The entry was found: do we need to insert, update or both?
			{
				if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.DimensionEntryFound")+data.returnRowMeta.getString(returnRow)); //$NON-NLS-1$
                
				// What's the key?  The first value of the return row
				technicalKey = data.returnRowMeta.getInteger(returnRow, 0);
				valueVersion = data.returnRowMeta.getInteger(returnRow, 1); 
                
				// Date range: ]-oo,+oo[ 
				valueDateFrom = meta.getMinDate();
				valueDateTo   = meta.getMaxDate();

				// The other values, we compare with
				int cmp;
				
				// If everything is the same: don't do anything
				// If one of the fields is different: insert or update
				// If all changed fields have update = Y, update
				// If one of the changed fields has update = N, insert

				boolean insert=false;
				boolean identical=true;
				boolean punch=false;
				
				for (int i=0;i<meta.getFieldStream().length;i++)
				{
                    ValueMetaInterface v1  = rowMeta.getValueMeta(data.fieldnrs[i]);
                    Object valueData1 = row[data.fieldnrs[i]]; 
                    ValueMetaInterface v2  = data.returnRowMeta.getValueMeta(i+2);
                    Object valueData2 = returnRow[i+2];
                        
					cmp = v1.compare(valueData1, v2, valueData2);
					  
					  // Not the same and update = 'N' --> insert
					  if (cmp!=0) identical=false;
                      
                      // Field flagged for insert: insert
					  if (cmp!=0 && meta.getFieldUpdate()[i]==DimensionLookupMeta.TYPE_UPDATE_DIM_INSERT)
					  { 
					  	insert=true;
					  }
                      
                      // Field flagged for punchthrough
					  if (cmp!=0 && meta.getFieldUpdate()[i]==DimensionLookupMeta.TYPE_UPDATE_DIM_PUNCHTHROUGH) 
                      {
                            punch=true;
                      }
					  
					  logRowlevel(Messages.getString("DimensionLookup.Log.ComparingValues",""+v1,""+v2,String.valueOf(cmp),String.valueOf(identical),String.valueOf(insert),String.valueOf(punch))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				}
				
				if (!insert)  // Just an update of row at key = valueKey
				{
					if (!identical)
					{
						if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.UpdateRowWithValues")+row); //$NON-NLS-1$
						/*
						 * UPDATE d_customer
						 * SET    fieldlookup[] = row.getValue(fieldnrs)
						 * WHERE  returnkey = dimkey
						 */
						dimUpdate(rowMeta, row, technicalKey);
						linesUpdated++;
                        
                        // We need to capture this change in the cache as well...
                        if (meta.getCacheSize()>=0)
                        {
                            Object[] values = getCacheValues(rowMeta, row, technicalKey, valueVersion, valueDateFrom, valueDateTo);
                            addToCache(lookupRow, values);
                        }
					}
					else
					{
						if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.SkipLine")); //$NON-NLS-1$
						// Don't do anything, everything is file in de dimension.
						linesSkipped++;
					}
				}
				else
				{
					if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.InsertNewVersion")+technicalKey.toString()); //$NON-NLS-1$
					
					valueDateFrom = data.valueDateNow;
					valueDateTo   = data.max_date; //$NON-NLS-1$

					// First try to use an AUTOINCREMENT field
					if (meta.getDatabaseMeta().supportsAutoinc() && isAutoIncrement())
					{
						technicalKey=new Long(0L); // value to accept new key...
					}
					else
					// Try to get the value by looking at a SEQUENCE (oracle mostly)
					if (meta.getDatabaseMeta().supportsSequences() && meta.getSequenceName()!=null && meta.getSequenceName().length()>0)
					{
						technicalKey=data.db.getNextSequenceValue(meta.getSchemaName(), meta.getSequenceName(), meta.getKeyField());
						if (technicalKey!=null && log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.FoundNextSequence2")+technicalKey.toString()); //$NON-NLS-1$
					}
					else
					// Use our own sequence here...
					{
						// What's the next value for the technical key?
                        technicalKey = data.db.getNextValue(getTransMeta().getCounters(), meta.getSchemaName(), meta.getTableName(), meta.getKeyField());
					}

					dimInsert( rowMeta, row, technicalKey, false, valueVersion, valueDateFrom, valueDateTo ); 
					linesOutput++;
                    
                    // We need to capture this change in the cache as well...
                    if (meta.getCacheSize()>=0)
                    {
                        Object[] values = getCacheValues(rowMeta, row, technicalKey, valueVersion, valueDateFrom, valueDateTo);
                        addToCache(lookupRow, values);
                    }
				}
				if (punch) // On of the fields we have to punch through has changed!
				{
					/*
					 * This means we have to update all versions:
					 * 
					 * UPDATE dim SET punchf1 = val1, punchf2 = val2, ...
					 * WHERE  fieldlookup[] = ?
					 * ;
					 * 
					 * --> update ALL versions in the dimension table.
					 */
					dimPunchThrough( rowMeta, row );
					linesUpdated++;
				}
				
				returnRow = new Object[data.returnRowMeta.size()];
				returnRow[0] = technicalKey;
				if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.TechnicalKey")+technicalKey); //$NON-NLS-1$
			}
		}
		
		if (log.isRowLevel()) logRowlevel(Messages.getString("DimensionLookup.Log.AddValuesToRow")+data.returnRowMeta.getString(returnRow)); //$NON-NLS-1$
        
        
        // Copy the results to the output row...
        //
        // First copy the input row values to the output...
        for (int i=0;i<rowMeta.size();i++) outputRow[i] = row[i];

        int outputIndex = rowMeta.size();
        int inputIndex = 0;
        
        // Then the technical key...
        outputRow[outputIndex] = returnRow[inputIndex];
        outputIndex++;
        inputIndex++;
        
        // Then get the "extra fields"...
        if (meta.getCacheSize()>=0 && !meta.isUpdate()) // don't return date from-to fields.
        {
            inputIndex+=2;
        }

        while (inputIndex<returnRow.length && outputIndex<outputRow.length)
		{
			outputRow[outputIndex] = returnRow[inputIndex];
            outputIndex++;
            inputIndex++;
		}

		//
		// Finaly, check the date range!
        /*
         * TODO: WTF is this???
		Value date;
		if (data.datefieldnr>=0) date = row.getValue(data.datefieldnr);
		else				date = new Value("date", new Date()); // system date //$NON-NLS-1$
		
		if (data.min_date.compare(date)>0) data.min_date.setValue( date.getDate() ); 
		if (data.max_date.compare(date)<0) data.max_date.setValue( date.getDate() ); 
         */
        
        return outputRow;
	}
    
    /**
     * table: dimension table keys[]: which dim-fields do we use to look up key? retval: name of the key to return
     * datefield: do we have a datefield? datefrom, dateto: date-range, if any.
     */
    public void setDimLookup(RowMetaInterface rowMeta) throws KettleDatabaseException
    {
        DatabaseMeta databaseMeta = meta.getDatabaseMeta();
        
        data.returnRowMeta = new RowMeta();
        data.lookupRowMeta = new RowMeta();
    
        /* 
         * SELECT <tk>, <version>, ... 
         * FROM <table> 
         * WHERE key1=keys[1] 
         * AND key2=keys[2] ...
         * AND <datefield> BETWEEN <datefrom> AND <dateto>
         * ;
         * 
         */
        String sql = "SELECT "+databaseMeta.quoteField(meta.getKeyField())+", "+databaseMeta.quoteField(meta.getVersionField());
        data.returnRowMeta.addValueMeta( new ValueMeta(meta.getKeyField(), ValueMetaInterface.TYPE_INTEGER) );
        data.returnRowMeta.addValueMeta( new ValueMeta(meta.getVersionField(), ValueMetaInterface.TYPE_INTEGER) );
        
        if (!Const.isEmpty(meta.getFieldLookup()))
        {
            for (int i=0;i<meta.getFieldLookup().length;i++)
            {
                if (!Const.isEmpty(meta.getFieldLookup()[i]))
                {
                    sql+=", "+databaseMeta.quoteField(meta.getFieldLookup()[i]);
                    ValueMetaInterface valueMeta = (ValueMetaInterface) rowMeta.getValueMeta( data.fieldnrs[i] ).clone();
                    
                    if (!Const.isEmpty( meta.getFieldStream()[i] ) && !meta.getFieldLookup()[i].equals(meta.getFieldStream()[i]))
                    {
                        sql+=" AS "+databaseMeta.quoteField(meta.getFieldStream()[i]);
                        valueMeta.setName(meta.getFieldStream()[i]);
                    }
                    
                    data.returnRowMeta.addValueMeta( valueMeta );
                }
            }
        }
        if (meta.getCacheSize()>=0)
        {
            sql+=", "+databaseMeta.quoteField(meta.getDateFrom())+", "+databaseMeta.quoteField(meta.getDateTo());
            data.returnRowMeta.addValueMeta( new ValueMeta(meta.getDateFrom(), ValueMetaInterface.TYPE_DATE) );
            data.returnRowMeta.addValueMeta( new ValueMeta(meta.getDateTo(), ValueMetaInterface.TYPE_DATE) );
        }
        
        sql+= " FROM "+data.schemaTable+" WHERE ";
        
        for (int i=0;i<meta.getKeyLookup().length;i++)
        {
            if (i!=0) sql += " AND ";
            sql += databaseMeta.quoteField(meta.getKeyLookup()[i])+" = ? ";
            data.lookupRowMeta.addValueMeta( rowMeta.getValueMeta(data.keynrs[i]) );
        }
        
        sql += " AND ? >= "+databaseMeta.quoteField(meta.getDateFrom())+" AND ? < "+databaseMeta.quoteField(meta.getDateTo());
        data.lookupRowMeta.addValueMeta( new ValueMeta(meta.getDateFrom(), ValueMetaInterface.TYPE_DATE) );
        data.lookupRowMeta.addValueMeta( new ValueMeta(meta.getDateTo(), ValueMetaInterface.TYPE_DATE) );
    
        try
        {
            log.logDetailed(toString(), "Dimension Lookup setting preparedStatement to ["+sql+"]");
            data.prepStatementLookup=data.db.getConnection().prepareStatement(databaseMeta.stripCR(sql));
            if (databaseMeta.supportsSetMaxRows())
            {
                data.prepStatementLookup.setMaxRows(1); // alywas get only 1 line back!
            }
            if (databaseMeta.getDatabaseType()==DatabaseMeta.TYPE_DATABASE_MYSQL)
            {
                data.prepStatementLookup.setFetchSize(0); // Make sure to DISABLE Streaming Result sets
            }
            log.logDetailed(toString(), "Finished preparing dimension lookup statement.");
        }
        catch(SQLException ex) 
        {
            throw new KettleDatabaseException("Unable to prepare dimension lookup", ex);
        }
    }

    private boolean isAutoIncrement()
    {
        return techKeyCreation == CREATION_METHOD_AUTOINC;
    }
    
    
    // This inserts new record into dimension
    // Optionally, if the entry already exists, update date range from previous version
    // of the entry.
    // 
    public Long dimInsert( RowMetaInterface inputRowMeta, Object[] row, Long technicalKey, boolean newEntry, Long versionNr, Date dateFrom, Date dateTo ) throws KettleException
    {
        DatabaseMeta databaseMeta = meta.getDatabaseMeta();
        
        if (data.prepStatementInsert==null && data.prepStatementUpdate==null) // first time: construct prepared statement
        {
            RowMetaInterface insertRowMeta = new RowMeta();

            /* Construct the SQL statement...
             *
             * INSERT INTO 
             * d_customer(keyfield, versionfield, datefrom,    dateto,   key[], fieldlookup[])
             * VALUES    (val_key ,val_version , val_datfrom, val_datto, keynrs[], fieldnrs[])
             * ;
             */
             
            String sql="INSERT INTO "+data.schemaTable+"( ";
            
            if (!isAutoIncrement())
            {
                sql+=databaseMeta.quoteField(meta.getKeyField())+", "; // NO AUTOINCREMENT
                insertRowMeta.addValueMeta( data.outputRowMeta.getValueMeta(inputRowMeta.size()) ); // the first return value after the input 
            }
            else
            {
                if (databaseMeta.getDatabaseType()==DatabaseMeta.TYPE_DATABASE_INFORMIX) 
                {
                    sql+="0, "; // placeholder on informix!    
                }
            }
            
            sql+=databaseMeta.quoteField(meta.getVersionField())+", "+databaseMeta.quoteField(meta.getDateFrom())+", "+databaseMeta.quoteField(meta.getDateTo());
            insertRowMeta.addValueMeta( new ValueMeta(meta.getVersionField(), ValueMetaInterface.TYPE_INTEGER));
            insertRowMeta.addValueMeta( new ValueMeta(meta.getDateFrom(), ValueMetaInterface.TYPE_DATE));
            insertRowMeta.addValueMeta( new ValueMeta(meta.getDateTo(), ValueMetaInterface.TYPE_DATE));
            
            for (int i=0;i<meta.getKeyLookup().length;i++)
            {
                sql+=", "+databaseMeta.quoteField(meta.getKeyLookup()[i]);
                insertRowMeta.addValueMeta( inputRowMeta.getValueMeta( data.keynrs[i] ));
            }
            
            for (int i=0;i<meta.getFieldLookup().length;i++)
            {
                sql+=", "+databaseMeta.quoteField(meta.getFieldLookup()[i]);
                insertRowMeta.addValueMeta( inputRowMeta.getValueMeta( data.fieldnrs[i] ));

            }
            sql+=") VALUES (";
            
            if (!isAutoIncrement())
            {
                sql+="?, ";
            }
            sql+="?, ?, ?";

            for (int i=0;i<data.keynrs.length;i++)
            {
                sql+=", ?";
            }
            
            for (int i=0;i<data.fieldnrs.length;i++)
            {
                sql+=", ?";
            }
            sql+=" )";
            
            try
            {
                if (!Const.isEmpty(meta.getKeyField()))
                {
                    log.logDetailed(toString(), "SQL w/ return keys=["+sql+"]");
                    data.prepStatementInsert=data.db.getConnection().prepareStatement(databaseMeta.stripCR(sql), Statement.RETURN_GENERATED_KEYS);
                }
                else
                {
                    log.logDetailed(toString(), "SQL=["+sql+"]");
                    data.prepStatementInsert=data.db.getConnection().prepareStatement(databaseMeta.stripCR(sql));
                }
                //pstmt=con.prepareStatement(sql, new String[] { "klant_tk" } );
            }
            catch(SQLException ex) 
            {
                throw new KettleDatabaseException("Unable to prepare dimension insert :"+Const.CR+sql, ex);
            }

            /* 
            * UPDATE d_customer
            * SET    dateto = val_datnow
            * WHERE  keylookup[] = keynrs[]
            * AND    versionfield = val_version - 1
            * ;
            */

            RowMetaInterface updateRowMeta = new RowMeta();
            
            String sql_upd="UPDATE "+data.schemaTable+Const.CR+"SET "+databaseMeta.quoteField(meta.getDateTo())+" = ?"+Const.CR;
            updateRowMeta.addValueMeta( new ValueMeta(meta.getDateTo(), ValueMetaInterface.TYPE_DATE));
            
            sql_upd+="WHERE ";
            for (int i=0;i<meta.getKeyLookup().length;i++)
            {
                if (i>0) sql_upd+="AND   ";
                sql_upd+=databaseMeta.quoteField(meta.getKeyLookup()[i])+" = ?"+Const.CR;
                updateRowMeta.addValueMeta( inputRowMeta.getValueMeta( data.keynrs[i] ));
            }
            sql_upd+="AND   "+databaseMeta.quoteField(meta.getVersionField())+" = ? ";
            updateRowMeta.addValueMeta( new ValueMeta(meta.getVersionField(), ValueMetaInterface.TYPE_INTEGER));

            try
            {
                log.logDetailed(toString(), "Preparing update: "+Const.CR+sql_upd+Const.CR);
                data.prepStatementUpdate=data.db.getConnection().prepareStatement(databaseMeta.stripCR(sql_upd));
            }
            catch(SQLException ex) 
            {
                throw new KettleDatabaseException("Unable to prepare dimension update :"+Const.CR+sql_upd, ex);
            }
            
            data.insertRowMeta = insertRowMeta;
            data.updateRowMeta = updateRowMeta;
        }
        
        Object[] insertRow=new Object[data.insertRowMeta.size()];
        int insertIndex=0;
        if (!isAutoIncrement()) 
        {
            insertRow[insertIndex] = technicalKey;
            insertIndex++;
        }
        if (!newEntry)
        {
            insertRow[insertIndex] = new Long( versionNr.longValue() + 1 );
            insertIndex++;
        }
        else
        {
            insertRow[insertIndex] = versionNr;
            insertIndex++;
        }
        
        insertRow[insertIndex] = dateFrom;
        insertIndex++;
        insertRow[insertIndex] = dateTo;
        insertIndex++;
        
        for (int i=0;i<data.keynrs.length;i++)
        {
            insertRow[insertIndex] = row[ data.keynrs[i] ];
            insertIndex++;
        }
        for (int i=0;i<data.fieldnrs.length;i++)
        {
            insertRow[insertIndex] = row[ data.fieldnrs[i] ];
            insertIndex++;
        }
        
        if (log.isDebug()) log.logDebug(toString(), "rins, size="+data.insertRowMeta.size()+", values="+data.insertRowMeta.getString(insertRow));
        
        // INSERT NEW VALUE!
        data.db.setValues(data.insertRowMeta, insertRow, data.prepStatementInsert);
        data.db.insertRow(data.prepStatementInsert);
            
        if (log.isDebug()) log.logDebug(toString(), "Row inserted!");
        if (isAutoIncrement())
        {
            try
            {
                Object[] keys = data.db.getGeneratedKeys(data.prepStatementInsert);
                if (keys.length>0)
                {
                    technicalKey = (Long) keys[0];
                }
                else
                {
                    throw new KettleDatabaseException("Unable to retrieve value of auto-generated technical key : no value found!");
                }
            }
            catch(Exception e)
            {
                throw new KettleDatabaseException("Unable to retrieve value of auto-generated technical key : unexpected error: ", e);
            }
        }
        
        if (!newEntry) // we have to update the previous version in the dimension! 
        {
            /* 
            * UPDATE d_customer
            * SET    dateto = val_datfrom
            * WHERE  keylookup[] = keynrs[]
            * AND    versionfield = val_version - 1
            * ;
            */
            Object[] updateRow = new Object[data.updateRowMeta.size()];
            int updateIndex=0;
            
            updateRow[updateIndex] = dateFrom;
            updateIndex++;
            
            for (int i=0;i<data.keynrs.length;i++)
            {
                updateRow[updateIndex] = row[data.keynrs[i] ];
                updateIndex++;
            }
            
            updateRow[updateIndex] = versionNr;
            updateIndex++;
            
            if (log.isRowLevel()) log.logRowlevel(toString(), "UPDATE using rupd="+data.updateRowMeta.getString(updateRow));

            // UPDATE VALUES
            data.db.setValues(data.updateRowMeta, updateRow, data.prepStatementUpdate);  // set values for update
            if (log.isDebug()) log.logDebug(toString(), "Values set for update ("+data.updateRowMeta.size()+")");
            data.db.insertRow(data.prepStatementUpdate); // do the actual update
            if (log.isDebug()) log.logDebug(toString(), "Row updated!");
        }
        
        return technicalKey;
    }
    
    public void dimUpdate(RowMetaInterface rowMeta, Object[] row, Long dimkey) throws KettleDatabaseException
    {
        if (data.prepStatementDimensionUpdate==null) // first time: construct prepared statement
        {
            data.dimensionUpdateRowMeta = new RowMeta();
            
            // Construct the SQL statement...
            /*
             * UPDATE d_customer
             * SET    fieldlookup[] = row.getValue(fieldnrs)
             * WHERE  returnkey = dimkey
             * ;
             */
             
            String sql="UPDATE "+data.schemaTable+Const.CR+"SET ";
            
            for (int i=0;i<meta.getFieldLookup().length;i++)
            {
                if (i>0) sql+=", "; else sql+="  ";
                sql+= meta.getDatabaseMeta().quoteField(meta.getFieldLookup()[i])+" = ?"+Const.CR;
                data.dimensionUpdateRowMeta.addValueMeta( rowMeta.getValueMeta(data.keynrs[i]) );
            }
            sql+="WHERE  "+meta.getDatabaseMeta().quoteField(meta.getKeyField())+" = ?";
            data.dimensionUpdateRowMeta.addValueMeta( new ValueMeta(meta.getKeyField(), ValueMetaInterface.TYPE_INTEGER) ); // The tk
            
            try
            {
                if (log.isDebug()) log.logDebug(toString(), "Preparing statement: ["+sql+"]");
                data.prepStatementDimensionUpdate=data.db.getConnection().prepareStatement(meta.getDatabaseMeta().stripCR(sql));
            }
            catch(SQLException ex) 
            {
                throw new KettleDatabaseException("Couldn't prepare statement :"+Const.CR+sql, ex);
            }
        }
        
        // Assemble information
        // New
        Object[] dimensionUpdateRow = new Object[data.dimensionUpdateRowMeta.size()];
        for (int i=0;i<data.fieldnrs.length;i++)
        {
            dimensionUpdateRow[i] = row[data.fieldnrs[i]];
        }
        dimensionUpdateRow[data.fieldnrs.length] = dimkey;
        
        data.db.setValues(data.dimensionUpdateRowMeta, dimensionUpdateRow, data.prepStatementDimensionUpdate);
        data.db.insertRow(data.prepStatementDimensionUpdate);
    }

    
    // This updates all versions of a dimension entry.
    // 
    public void dimPunchThrough(RowMetaInterface rowMeta, Object[] row) throws KettleDatabaseException
    {
        if (data.prepStatementPunchThrough==null) // first time: construct prepared statement
        {
            data.punchThroughRowMeta = new RowMeta();
            
            /* 
            * UPDATE table
            * SET    punchv1 = fieldx, ...
            * WHERE  keylookup[] = keynrs[]
            * ;
            */

            String sql_upd="UPDATE "+data.schemaTable+Const.CR;
            sql_upd+="SET ";
            boolean first=true;
            for (int i=0;i<meta.getFieldLookup().length;i++)
            {
                if (meta.getFieldUpdate()[i]==DimensionLookupMeta.TYPE_UPDATE_DIM_PUNCHTHROUGH)
                {
                    if (!first) sql_upd+=", "; else sql_upd+="  ";
                    first=false;
                    sql_upd+=meta.getFieldLookup()[i]+" = ?"+Const.CR;
                    data.punchThroughRowMeta.addValueMeta( rowMeta.getValueMeta(data.fieldnrs[i]) );
                }
            }
            sql_upd+="WHERE ";
            for (int i=0;i<meta.getKeyLookup().length;i++)
            {
                if (i>0) sql_upd+="AND   ";
                sql_upd+=meta.getKeyLookup()[i]+" = ?"+Const.CR;
                data.punchThroughRowMeta.addValueMeta( rowMeta.getValueMeta(data.keynrs[i]) );
            }

            try
            {
                data.prepStatementPunchThrough=data.db.getConnection().prepareStatement(meta.getDatabaseMeta().stripCR(sql_upd));
            }
            catch(SQLException ex) 
            {
                throw new KettleDatabaseException("Unable to prepare dimension punchThrough update statement : "+Const.CR+sql_upd, ex);
            }
        }
        
        Object[] punchThroughRow = new Object[data.punchThroughRowMeta.size()];
        int punchIndex=0;
        
        for (int i=0;i<meta.getFieldLookup().length;i++)
        {
            if (meta.getFieldUpdate()[i]==DimensionLookupMeta.TYPE_UPDATE_DIM_PUNCHTHROUGH)
            {
                punchThroughRow[punchIndex] = row[ data.fieldnrs[i] ];
                punchIndex++;
            }
        }
        for (int i=0;i<data.keynrs.length;i++)
        {
            punchThroughRow[punchIndex] = row[ data.keynrs[i] ];
            punchIndex++;
        }

        // UPDATE VALUES
        data.db.setValues(data.punchThroughRowMeta, punchThroughRow, data.prepStatementPunchThrough);  // set values for update
        data.db.insertRow(data.prepStatementPunchThrough); // do the actual punch through update
    }
	
    /**
     * Keys:
     *   - natural key fields
     * Values:
     *   - Technical key
     *   - lookup fields / extra fields (allows us to compare or retrieve)
     *   - Date_from
     *   - Date_to
     *   
     * @param row The input row
     * @param technicalKey the technical key value
     * @param valueDateFrom the start of valid date range
     * @param valueDateTo the end of the valid date range
     * @return the values to store in the cache as a row.
     */
    private Object[] getCacheValues(RowMetaInterface rowMeta, Object[] row, Long technicalKey, Long valueVersion, Date valueDateFrom, Date valueDateTo)
    {
        Object[] cacheValues = new Object[data.cacheValueRowMeta.size()];
        int cacheIndex = 0;
        
        cacheValues[cacheIndex] = technicalKey;
        cacheIndex++;
        
        cacheValues[cacheIndex] = valueVersion;
        cacheIndex++;
        
        for (int i=0;i<data.fieldnrs.length;i++)
        {
            cacheValues[cacheIndex] = row[ data.fieldnrs[i] ];
            cacheIndex++;
        }

        cacheValues[cacheIndex] = valueDateFrom;
        cacheIndex++;
        
        cacheValues[cacheIndex] = valueDateTo;
        cacheIndex++;
        
        return cacheValues;
    }

    /**
     * Adds a row to the cache
     * In case we are doing updates, we need to store the complete rows from the database.
     * These are the values we need to store
     * 
     * Key:
     *   - natural key fields
     * Value:
     *   - Technical key
     *   - lookup fields / extra fields (allows us to compare or retrieve)
     *   - Date_from
     *   - Date_to
     * 
     * @param keyValues
     * @param returnValues
     * @throws KettleValueException 
     */
	private void addToCache(Object[] keyValues, Object[] returnValues) throws KettleValueException
    {
        // store it in the cache if needed.
        data.cache.putAgain(RowMeta.extractData(data.cacheKeyRowMeta, keyValues), RowMeta.extractData(data.cacheValueRowMeta, returnValues));
        
        // check if the size is not too big...
        // Allow for a buffer overrun of 20% and then remove those 20% in one go.
        // Just to keep performance in track.
        //
        int tenPercent = meta.getCacheSize()/10;
        if (meta.getCacheSize()>0 && data.cache.size()>meta.getCacheSize()+tenPercent)
        {
            // Which cache entries do we delete here?
            // We delete those with the lowest technical key...
            // Those would arguably be the "oldest" dimension entries.
            // Oh well... Nothing is going to be perfect here...
            // 
            // Getting the lowest 20% requires some kind of sorting algorithm and I'm not sure we want to do that.
            // Sorting is slow and even in the best case situation we need to do 2 passes over the cache entries...
            //
            // Perhaps we should get 20% random values and delete everything below the lowest but one TK.
            //
            List keys = data.cache.getKeys();
            int sizeBefore = keys.size();
            List samples = new ArrayList();
            
            // Take 10 sample technical keys....
            int stepsize=keys.size()/5;
            if (stepsize<1) stepsize=1; //make shure we have no endless loop
            for (int i=0;i<keys.size();i+=stepsize)
            {
                byte[] key = (byte[]) keys.get(i);
                byte[] value = data.cache.get(key);
                if (value!=null)
                {
                    Object[] values = RowMeta.getRow(data.cacheValueRowMeta, value);
                    Long tk = data.cacheValueRowMeta.getInteger(values, 0);
                    samples.add(tk);
                }
            }
            // Sort these 5 elements...
            Collections.sort(samples);
            
            // What is the smallest?
            // Take the second, not the fist in the list, otherwise we would be removing a single entry = not good.
            if (samples.size()>1) {
            	data.smallestCacheKey = ((Long) samples.get(1)).longValue();
            } else { // except when there is only one sample
            	data.smallestCacheKey = ((Long) samples.get(0)).longValue();
            }
            
            // Remove anything in the cache <= smallest.
            // This makes it almost single pass...
            // This algorithm is not 100% correct, but I guess it beats sorting the whole cache all the time.
            //
            for (int i=0;i<keys.size();i++)
            {
                byte[] key = (byte[]) keys.get(i);
                byte[] value = data.cache.get(key);
                if (value!=null)
                {
                    Object[] values = RowMeta.getRow(data.cacheValueRowMeta, value);
                    long tk = data.cacheValueRowMeta.getInteger(values, 0).longValue();
                    if (tk<=data.smallestCacheKey)
                    {
                        data.cache.remove(key); // this one has to go.
                    }
                }
            }
            
            int sizeAfter = data.cache.size();
            logDetailed("Reduced the lookup cache from "+sizeBefore+" to "+sizeAfter+" rows.");
        }
        
        if (log.isRowLevel()) logRowlevel("Cache store: key="+keyValues+"    values="+returnValues);
    }

    private Object[] getFromCache(Object[] keyValues, Date dateValue) throws KettleValueException
    {
        byte[] value = data.cache.get(RowMeta.extractData(data.cacheKeyRowMeta, keyValues));
        if (value!=null) 
        {
            Object[] row = RowMeta.getRow(data.cacheValueRowMeta, value);
            
            // See if the dateValue is between the from and to date ranges...
            // The last 2 values are from and to
            long time = dateValue.getTime();
            long from = ((Long)row[row.length-2]).longValue(); 
            long to   = ((Long)row[row.length-1]).longValue(); 
            if (time>=from && time<to) // sanity check to see if we have the right version
            {
                if (log.isRowLevel()) logRowlevel("Cache hit: key="+data.cacheKeyRowMeta.getString(keyValues)+"  values="+data.cacheValueRowMeta.getString(row));
                return row;
            }
        }
        return null;
    }


    
    public void checkDimZero() throws KettleException
    {
        DatabaseMeta databaseMeta = meta.getDatabaseMeta();
        
        int start_tk = databaseMeta.getNotFoundTK(isAutoIncrement());
                
        String sql = "SELECT count(*) FROM "+data.schemaTable+" WHERE "+databaseMeta.quoteField(meta.getKeyField())+" = "+start_tk;
        RowMetaAndData r = data.db.getOneRow(sql); 
        Long count = r.getRowMeta().getInteger(r.getData(), 0);
        if (count.longValue() == 0)
        {
            try
            {
                String isql;
                if (!databaseMeta.supportsAutoinc() || !isAutoIncrement())
                {
                    isql = "insert into "+data.schemaTable+"("+databaseMeta.quoteField(meta.getKeyField())+", "+databaseMeta.quoteField(meta.getVersionField())+") values (0, 1)";
                }
                else
                {
                    switch(databaseMeta.getDatabaseType())
                    {
                    case DatabaseMeta.TYPE_DATABASE_CACHE       :
                    case DatabaseMeta.TYPE_DATABASE_GUPTA     :
                    case DatabaseMeta.TYPE_DATABASE_ORACLE      :  isql = "insert into "+data.schemaTable+"("+databaseMeta.quoteField(meta.getKeyField())+", "+databaseMeta.quoteField(meta.getVersionField())+") values (0, 1)"; break; 
                    case DatabaseMeta.TYPE_DATABASE_INFORMIX    : 
                    case DatabaseMeta.TYPE_DATABASE_MYSQL       :  isql = "insert into "+data.schemaTable+"("+databaseMeta.quoteField(meta.getKeyField())+", "+databaseMeta.quoteField(meta.getVersionField())+") values (1, 1)"; break;
                    case DatabaseMeta.TYPE_DATABASE_MSSQL       :  
                    case DatabaseMeta.TYPE_DATABASE_DB2         : 
                    case DatabaseMeta.TYPE_DATABASE_DBASE       :  
                    case DatabaseMeta.TYPE_DATABASE_GENERIC     :  
                    case DatabaseMeta.TYPE_DATABASE_SYBASE      :
                    case DatabaseMeta.TYPE_DATABASE_ACCESS      :  
                    case DatabaseMeta.TYPE_DATABASE_DERBY       :  isql = "insert into "+data.schemaTable+"("+databaseMeta.quoteField(meta.getVersionField())+") values (1)"; break;
                    default: isql = "insert into "+data.schemaTable+"("+databaseMeta.quoteField(meta.getKeyField())+", "+databaseMeta.quoteField(meta.getVersionField())+") values (0, 1)"; break;
                    }                   
                }
                
                data.db.execStatement(databaseMeta.stripCR(isql));
            }
            catch(KettleException e)
            {
                throw new KettleDatabaseException("Error inserting 'unknown' row in dimension ["+data.schemaTable+"] : "+sql, e);
            }
        }
    }

    
	
	public boolean init(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta=(DimensionLookupMeta)smi;
		data=(DimensionLookupData)sdi;

		if (super.init(smi, sdi))
		{
			data.min_date = meta.getMinDate(); //$NON-NLS-1$
			data.max_date = meta.getMaxDate(); //$NON-NLS-1$

			data.db=new Database(meta.getDatabaseMeta());
			try
			{
                if (getTransMeta().isUsingUniqueConnections())
                {
                    synchronized (getTrans()) { data.db.connect(getTrans().getThreadName(), getPartitionID()); }
                }
                else
                {
                    data.db.connect(getPartitionID());
                }
				
				logBasic(Messages.getString("DimensionLookup.Log.ConnectedToDB")); //$NON-NLS-1$
				data.db.setCommit(meta.getCommitSize());
				
				return true;
			}
			catch(KettleException ke)
			{
				logError(Messages.getString("DimensionLookup.Log.ErrorOccurredInProcessing")+ke.getMessage()); //$NON-NLS-1$
			}
		}
		return false;
	}
	
	public void dispose(StepMetaInterface smi, StepDataInterface sdi)
	{
	    meta = (DimensionLookupMeta)smi;
	    data = (DimensionLookupData)sdi;
	    
        try
        {
            if (!data.db.isAutoCommit())
            {
                if (getErrors()==0)
                {
                    data.db.commit();
                }
                else
                {
                    data.db.rollback();
                }
            }
        }
        catch(KettleDatabaseException e)
        {
            logError(Messages.getString("DimensionLookup.Log.ErrorOccurredInProcessing")+e.getMessage()); //$NON-NLS-1$
        }
        
	    data.db.disconnect();
	    
	    super.dispose(smi, sdi);
	}
	
	//
	// Run is were the action happens!
	public void run()
	{
		try
		{
			logBasic(Messages.getString("DimensionLookup.Log.StartingToRun")); //$NON-NLS-1$
			while (processRow(meta, data) && !isStopped());
		}
		catch(Exception e)
		{
			logError(Messages.getString("DimensionLookup.Log.UnexpectedError", e.toString())); //$NON-NLS-1$ //$NON-NLS-2$
            logError(Const.getStackTracker(e));
            setErrors(1);
			stopAll();
		}
		finally
		{
			dispose(meta, data);
			logSummary();
			markStop();
		}
	}
}
