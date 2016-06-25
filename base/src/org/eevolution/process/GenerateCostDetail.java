/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * Copyright (C) 2003-2010 e-Evolution,SC. All Rights Reserved.               *
 * Contributor(s): victor.perez@e-evolution.com http://www.e-evolution.com    *
 *****************************************************************************/

package org.eevolution.process;

import org.adempiere.engine.CostEngineFactory;
import org.adempiere.engine.CostingMethodFactory;
import org.adempiere.engine.StandardCostingMethod;
import org.adempiere.exceptions.FillMandatoryException;
import org.compiere.model.I_M_Cost;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MCostDetail;
import org.compiere.model.MCostElement;
import org.compiere.model.MCostType;
import org.compiere.model.MInOutLine;
import org.compiere.model.MLandedCostAllocation;
import org.compiere.model.MMatchInv;
import org.compiere.model.MMatchPO;
import org.compiere.model.MProduct;
import org.compiere.model.MTransaction;
import org.compiere.model.Query;
import org.compiere.model.X_M_CostType;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Trx;
import org.eevolution.model.MPPCostCollector;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/**
 * Regenerate Cost Detail The Generate Cost Transaction process allows the
 * detailed cost calculation and cost generation beginning from a date. If you
 * have not yet set COGs Adjustment, you should execute this process before a
 * period's end in order to fix the cost layers.
 *
 * @author victor.perez@e-evolution.com, www.e-evolution.com
 * @author Yamel Senih, ysenih@erpcya.com, ERPCyA http://www.erpcya.com
 *			<li> FR [ 405 ] Wrong Syntax of Delete query in GenerateCostDetail.java
 *			@see https://github.com/adempiere/adempiere/issues/405
 */
public class GenerateCostDetail extends SvrProcess {
    /**
     * Parameters *
     */
    private int productId;
    private int costElementId;
    private int costTypeId;
    private int accountSchemaId;
    private Timestamp dateAccount;
    private Timestamp dateAccountTo;

    /**
     * Variables *
     */
    private ArrayList<Object> deleteParameters;
    private ArrayList<Object> resetCostParameters;
    private List<MAcctSchema> acctSchemas = new ArrayList<MAcctSchema>();
    private List<MCostType> costTypes = new ArrayList<MCostType>();
    private List<MCostElement> costElements = new ArrayList<MCostElement>();
    private StringBuffer deleteCostDetailWhereClause;
    private StringBuffer resetCostWhereClause;
    private List<Integer> deferredTransactionIds = new ArrayList<Integer>();
    private List<Integer> deferredProductIds = new ArrayList<Integer>();

    /**
     * Prepare - e.g., get Parameters.
     */
    protected void prepare() {
        ProcessInfoParameter[] parameters = getParameter();
        for (ProcessInfoParameter parameter : parameters) {
            String name = parameter.getParameterName();
            if (parameter.getParameter() == null)
                ;

            if (name.equals(MCostDetail.COLUMNNAME_C_AcctSchema_ID)) {
                accountSchemaId = parameter.getParameterAsInt();
            } else if (name.equals(MCostDetail.COLUMNNAME_M_CostType_ID)) {
                costTypeId = parameter.getParameterAsInt();
            } else if (name.equals(MCostDetail.COLUMNNAME_M_CostElement_ID)) {
                costElementId = parameter.getParameterAsInt();
            } else if (name.equals(MCostDetail.COLUMNNAME_M_Product_ID)) {
                productId = parameter.getParameterAsInt();
            } else if (name.equals(MCostDetail.COLUMNNAME_DateAcct)) {
                dateAccount = (Timestamp) parameter.getParameter();
                if (dateAccount == null)
                    throw new FillMandatoryException(
                            MCostDetail.COLUMNNAME_DateAcct);
                dateAccountTo = (Timestamp) parameter.getParameter_To();
                if (dateAccountTo == null)
                    dateAccountTo = new Timestamp(System.currentTimeMillis());
            }
        }
        if (dateAccount != null) {
            setup();
        }

    } // prepare

    /**
     * execute the Generate Cost Detail
     */
    protected String doIt() throws Exception {
        generateCostDetail();
        return "@Ok@";

    }

    /**
     * Delete Cost Detail
     *
     * @throws SQLException
     */
    private void deleteCostDetail(String trxName) throws SQLException {
        StringBuffer sqlDelete;
        //	BR [ 405 ]
        sqlDelete = new StringBuffer("DELETE FROM M_CostDetail WHERE ");
        sqlDelete.append(deleteCostDetailWhereClause);
        DB.executeUpdateEx(sqlDelete.toString(),
                deleteParameters.toArray(), trxName);
    }

    /**
     * Reset Cost Dimension
     * @param costingMethod
     * @param trxName
     * @throws SQLException
     */
    private void resetCostDimension(String costingMethod, String trxName) throws SQLException {
        StringBuffer sqlReset;
        sqlReset = new StringBuffer("UPDATE M_Cost SET ");

        // Delete M_Cost not for others than average
        if (MCostType.COSTINGMETHOD_AverageInvoice.equals(costingMethod)) {
            sqlReset.append(I_M_Cost.COLUMNNAME_CurrentCostPrice).append("=0.0,");
            sqlReset.append(I_M_Cost.COLUMNNAME_CurrentCostPriceLL).append("= 0.0,");
        }

        sqlReset.append(I_M_Cost.COLUMNNAME_CurrentQty).append("= 0.0,");
        sqlReset.append(I_M_Cost.COLUMNNAME_CumulatedAmt).append("= 0.0,");
        sqlReset.append(I_M_Cost.COLUMNNAME_CumulatedAmtLL).append("= 0.0,");
        sqlReset.append(I_M_Cost.COLUMNNAME_CumulatedQty).append("= 0.0 ");
        sqlReset.append(" WHERE ").append(resetCostWhereClause);
        DB.executeUpdateEx(sqlReset.toString(),
                resetCostParameters.toArray(), trxName);
    }


    /**
     * Setup the collections
     *
     * @throws SQLException
     */
    private void setup() {

        if (accountSchemaId > 0)
            acctSchemas.add(MAcctSchema.get(getCtx(), accountSchemaId, get_TrxName()));
        else
            acctSchemas = new ArrayList(Arrays.asList(MAcctSchema
                    .getClientAcctSchema(getCtx(), getAD_Client_ID(),
                            get_TrxName())));

        if (costTypeId > 0)
            costTypes.add(new MCostType(getCtx(), costTypeId,
                    get_TrxName()));
        else
            costTypes = MCostType.get(getCtx(), get_TrxName());

        if (costElementId > 0)
            costElements.add(MCostElement.get(getCtx(), costElementId));
        else
            costElements = MCostElement.getCostElement(getCtx(), get_TrxName());
    }

    /**
     * Apply Criteria for where clause
     *
     * @param accountSchemaId
     * @param costTypeId
     * @param costElementId
     * @param productId
     * @param dateAccount
     */
    private void applyCriteria(int accountSchemaId, int costTypeId,
                               int costElementId, int productId, Timestamp dateAccount, Timestamp dateAccountTo) {
        deleteParameters = new ArrayList<Object>();
        resetCostParameters = new ArrayList<Object>();
        deleteCostDetailWhereClause = new StringBuffer("1=1");
        resetCostWhereClause = new StringBuffer("1=1");

        if (accountSchemaId > 0) {
            deleteCostDetailWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_C_AcctSchema_ID)
                    .append("=? ");
            deleteParameters.add(accountSchemaId);
            resetCostWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_C_AcctSchema_ID)
                    .append("=? ");
            resetCostParameters.add(accountSchemaId);
        }
        if (costTypeId > 0) {
            deleteCostDetailWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_M_CostType_ID).append("=? ");
            deleteParameters.add(costTypeId);
            resetCostWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_M_CostType_ID).append("=? ");
            resetCostParameters.add(costTypeId);//SHW
        }
        if (costElementId > 0) {
            deleteCostDetailWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_M_CostElement_ID)
                    .append("=? ");
            deleteParameters.add(costElementId);
            resetCostWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_M_CostElement_ID)
                    .append("=? ");
            resetCostParameters.add(costElementId);//SHW
        }
        if (productId > 0) {
            deleteCostDetailWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_M_Product_ID).append("=? ");
            deleteParameters.add(productId);
            resetCostWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_M_Product_ID).append("=? ");
            resetCostParameters.add(productId);
        }
        if (dateAccount != null) {
            deleteCostDetailWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_DateAcct).append(">=? ");
            deleteParameters.add(dateAccount);
        }
        if (dateAccountTo != null) {
            deleteCostDetailWhereClause.append(" AND ")
                    .append(MCostDetail.COLUMNNAME_DateAcct).append("<=? ");
            deleteParameters.add(dateAccountTo);
        }
        //avoid rest cost dimension if not exist transaction conserve of last cost calculated
        resetCostWhereClause.append(" AND EXISTS ( SELECT 1 FROM RV_Transaction WHERE M_Product_ID=? AND TRUNC(DateAcct)>=? AND TRUNC(DateAcct)<=?)");
        resetCostParameters.add(productId);
        resetCostParameters.add(dateAccount);
        resetCostParameters.add(dateAccountTo);
        return;
    }

    public void generateCostDetail() {

        KeyNamePair[] transactions = getTransactionIdsByDateAcct();
       // System.out.println("Transaction to process : " + transactions.length);
        Integer process = 0;
        Integer productId = 0;
        boolean processNewProduct = true;
        Trx dbTransaction = null;

        try {

            //Process transaction
            for (KeyNamePair keyNamePair : transactions) {

                int transactionId = keyNamePair.getKey();
                int transactionProductId = new Integer(keyNamePair.getName());

                //Detected a new product
                if (productId != transactionProductId) {

                    //commit last transaction by product
                    if (dbTransaction != null) {
                        dbTransaction.commit(true);
                        dbTransaction.close();
                    }

                    productId = transactionProductId;
                    processNewProduct = true;

                    //Create new transaction for this product
                    dbTransaction = Trx.get(productId.toString(), true);
                }


                MTransaction transaction = new MTransaction(getCtx(), transactionId, dbTransaction.getTrxName());

                // for each Account Schema
                for (MAcctSchema accountSchema : acctSchemas) {
                    // for each Cost Type
                    for (MCostType costType : costTypes) {
                        // for each Cost Element
                        for (MCostElement costElement : costElements) {
                            if (processNewProduct) {
                                applyCriteria(accountSchema.getC_AcctSchema_ID(),
                                        costType.getM_CostType_ID(), costElement.getM_CostElement_ID(),
                                        productId, dateAccount, dateAccountTo);
                                deleteCostDetail(dbTransaction.getTrxName());
                                resetCostDimension(costType.getCostingMethod(), dbTransaction.getTrxName());
                                generateCostCollectorNotTransaction(productId, dbTransaction.getTrxName());
                                processNewProduct = false;

                                if (MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())
                                        || MCostType.COSTINGMETHOD_AveragePO.equals(costType.getCostingMethod())) {
                                    if (IsUsedInProduction(productId, dbTransaction.getTrxName()))
                                        deferredProductIds.add(productId);
                                }
                            }

                            if (deferredProductIds.contains(transaction.getM_Product_ID())) {
                                deferredTransactionIds.add(transactionId);
                                continue;
                            }

                            generateCostDetail(accountSchema, costType, costElement, transaction);
                        }
                    }
                }

                process++;
                //System.out.println("Transaction : " + transactionId + " Transaction Type :"+ transaction.getMovementType() + " record ..." + process);
            }

            if (dbTransaction != null) {
                dbTransaction.commit(true);
                dbTransaction.close();
                dbTransaction = null;
            }

            //process deferred transaction for Average Invoice or Average PO
            final Comparator<Integer> orderTransaction =
                    new Comparator<Integer>() {
                        public int compare(Integer t1, Integer t2) {
                            return t2.compareTo(t1);
                        }
                    };

            Collections.sort(deferredTransactionIds, orderTransaction);
                //process deferred transaction for Average Invoice or Average PO
            for (Integer transactionId : deferredTransactionIds) {
                for (MAcctSchema accountSchema : acctSchemas) {
                    // for each Cost Type
                    for (MCostType costType : costTypes) {
                        if (MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())
                        ||  MCostType.COSTINGMETHOD_AveragePO.equals(costType.getCostingMethod()))
                            ;
                        else
                            continue;
                        // for each Cost Element
                        for (MCostElement costElement : costElements) {
                                int transactionProductId = DB.getSQLValue(get_TrxName(), "SELECT M_Product_ID FROM M_Transaction WHERE M_Transaction_ID=?", transactionId);

                                //Detected a new product
                                if (productId != transactionProductId) {

                                    //commit last transaction by product
                                    if (dbTransaction != null) {
                                        dbTransaction.commit(true);
                                        dbTransaction.close();
                                    }

                                    productId = transactionProductId;

                                    //Create new transaction for this product
                                    dbTransaction = Trx.get(productId.toString(), true);

                                    MProduct product = MProduct.get(Env.getCtx(), productId);
                                    //System.out.println("Deferred Product : " + product.getValue() + " Name :" + product.getName());

                                }

                                MTransaction transaction = new MTransaction(getCtx(), transactionId, dbTransaction.getTrxName());
                                generateCostDetail(accountSchema, costType, costElement, transaction);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (dbTransaction != null) {
                dbTransaction.rollback();
                dbTransaction.close();
                dbTransaction = null;
                e.printStackTrace();
                addLog(e.getMessage());
            }
        } finally {
            if (dbTransaction != null) {
                dbTransaction.commit();
                dbTransaction.close();
                dbTransaction = null;
            }
        }
    }

    private boolean IsUsedInProduction(int productId, String trxName) {
        StringBuilder whereClause = new StringBuilder();
        whereClause.append(MTransaction.COLUMNNAME_M_Product_ID).append("=? AND ");
        whereClause.append(MTransaction.COLUMNNAME_MovementType).append("=?");

        return new Query(getCtx(), MTransaction.Table_Name, whereClause.toString(), trxName)
                .setClient_ID()
                .setParameters(productId, MTransaction.MOVEMENTTYPE_ProductionPlus)
                .match();
    }

    public void generateCostDetail(MAcctSchema accountSchema, MCostType costType, MCostElement costElement, MTransaction transaction) {

        //Create Cost Detail for this Transaction
        CostEngineFactory.getCostEngine(accountSchema.getAD_Client_ID())
                .createCostDetail(accountSchema, costType, costElement, transaction,
                        transaction.getDocumentLine(), true);
        CostEngineFactory.getCostEngine(accountSchema.getAD_Client_ID())
                .clearAccounting(accountSchema, transaction);

        // Calculate adjustment cost by variances in
        // invoices
        if (MTransaction.MOVEMENTTYPE_VendorReceipts.equals(transaction.getMovementType())) {

            MInOutLine line = (MInOutLine) transaction.getDocumentLine();

            if (MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
                //get purchase matches
                List<MMatchPO> orderMatches = MMatchPO
                        .getInOutLine(line);
                orderMatches.stream().forEach(match -> {
                    if (match.getM_Product_ID() == transaction.getM_Product_ID()
                    && match.getDateAcct().after(dateAccount)
                    && match.getDateAcct().before(dateAccountTo)) {
                        CostEngineFactory.getCostEngine(
                                accountSchema.getAD_Client_ID())
                                .createCostDetail(accountSchema, costType, costElement, transaction,
                                        match, true);
                    }
                });
                //get invoice matches
                List<MMatchInv> invoiceMatches = MMatchInv
                        .getInOutLine(line);
               invoiceMatches.forEach(match -> {
                    if (match.getM_Product_ID() == transaction.getM_Product_ID()
                    && match.getDateAcct().after(dateAccount)
                    && match.getDateAcct().before(dateAccountTo)) {
                        CostEngineFactory.getCostEngine(
                                accountSchema.getAD_Client_ID())
                                .createCostDetail(accountSchema, costType, costElement, transaction,
                                        match, true);
                    }
                });
            }

            //get landed allocation cost
            MLandedCostAllocation.getOfInOutline(line, costElement.getM_CostElement_ID()).stream().forEach(allocation -> {
                if (allocation.getDateAcct().after(dateAccount)
                 && allocation.getDateAcct().before(dateAccountTo))
                CostEngineFactory
                        .getCostEngine(accountSchema.getAD_Client_ID())
                        .createCostDetail(accountSchema, costType, costElement, transaction, allocation, true);
            });
        }
    }

    private void generateCostCollectorNotTransaction(int productId, String trxName)
            throws SQLException {
        List<MPPCostCollector> costCollectors = MPPCostCollector
                .getCostCollectorNotTransaction(getCtx(), productId, dateAccount, dateAccountTo, trxName);
        // Process Collector Cost Manufacturing
        for (MPPCostCollector costCollector : costCollectors) {
            for (MCostDetail costDetail : MCostDetail.getByCollectorCost(costCollector)) {
                costDetail.deleteEx(true);
            }

            final StandardCostingMethod standardCostingMethod = (StandardCostingMethod) CostingMethodFactory.get()
                    .getCostingMethod(X_M_CostType.COSTINGMETHOD_StandardCosting);

            if (MPPCostCollector.COSTCOLLECTORTYPE_UsegeVariance.equals(costCollector.getCostCollectorType()))
                standardCostingMethod.createUsageVariances(costCollector);
            else if (MPPCostCollector.COSTCOLLECTORTYPE_MethodChangeVariance.equals(costCollector.getCostCollectorType()))
                standardCostingMethod.createMethodVariances(costCollector);
            else if (MPPCostCollector.COSTCOLLECTORTYPE_RateVariance.equals(costCollector.getCostCollectorType()))
                standardCostingMethod.createRateVariances(costCollector);
            else if (MPPCostCollector.COSTCOLLECTORTYPE_ActivityControl.equals(costCollector.getCostCollectorType()))
                standardCostingMethod.createActivityControl(costCollector);
            //else
                //System.out.println("Cost Collector Type: " + costCollector.getCostCollectorType());
        }
    }

    private KeyNamePair[] getTransactionIdsByDateAcct() {
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<Object>();
        StringBuilder whereClause = new StringBuilder("WHERE ");
        whereClause.append(MCostDetail.COLUMNNAME_AD_Client_ID).append("=")
                .append(getAD_Client_ID()).append(" AND ");
        if (productId > 0) {
            whereClause.append(MCostDetail.COLUMNNAME_M_Product_ID)
                    .append("=?").append(" AND ");
            parameters.add(productId);
        }
        whereClause.append("TRUNC(").append(MCostDetail.COLUMNNAME_DateAcct).append(")>=?");
        parameters.add(dateAccount);

        if (dateAccountTo != null) {
            whereClause.append(" AND TRUNC(").append(MCostDetail.COLUMNNAME_DateAcct).append(")<=?");
            parameters.add(dateAccountTo);
        }

        sql.append("SELECT M_Transaction_ID , M_Product_ID FROM RV_Transaction ")
                .append(whereClause)
                .append(" ORDER BY M_Product_ID ,  TRUNC( DateAcct ) , M_Transaction_ID , SUBSTR(MovementType,2,1) ");
        //.append(" ORDER BY M_Product_ID , DateAcct , M_Transaction_ID");
        //System.out.append("SQL :" + sql);
        return DB.getKeyNamePairs(get_TrxName(), sql.toString(), false, parameters.toArray());
    }
}
