package com.ibm.bi.dml.runtime.controlprogram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

import org.nimble.exception.NimbleCheckedRuntimeException;

import com.ibm.bi.dml.packagesupport.ExternalFunctionInvocationInstruction;
import com.ibm.bi.dml.packagesupport.PackageFunction;
import com.ibm.bi.dml.packagesupport.PackageRuntimeException;
import com.ibm.bi.dml.parser.DataIdentifier;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.io.InputInfo;
import com.ibm.bi.dml.runtime.matrix.io.OutputInfo;
import com.ibm.bi.dml.sql.sqlcontrolprogram.ExecutionContext;
import com.ibm.bi.dml.utils.DMLRuntimeException;

/**
 * CP external function program block, that overcomes the need for 
 * BlockToCell and CellToBlock MR jobs by changing the contract for an external function.
 * If execlocation="CP", the implementation of an external function must read and write
 * matrices as InputInfo.BinaryBlockInputInfo and OutputInfo.BinaryBlockOutputInfo.
 * 
 * Furthermore, it extends ExternalFunctionProgramBlock with a base directory in order
 * to make it parallelizable, even in case of different JVMs. For this purpose every
 * external function must implement a <SET_BASE_DIR> method. 
 * 
 * @author mboehm
 *
 */
public class ExternalFunctionProgramBlockCP extends ExternalFunctionProgramBlock 
{
	private static String SET_BASE_DIR = "setBaseDir"; 
	private String _baseDir = null;
	
	/**
	 * Constructor that also provides otherParams that are needed for external
	 * functions. Remaining parameters will just be passed to constructor for
	 * function program block.
	 * 
	 * @param eFuncStat
	 * @throws DMLRuntimeException 
	 */
	public ExternalFunctionProgramBlockCP(Program prog,
			Vector<DataIdentifier> inputParams,
			Vector<DataIdentifier> outputParams,
			HashMap<String, String> otherParams,
			String baseDir) throws DMLRuntimeException {

		super(prog, inputParams, outputParams); //w/o instruction generation
		
		// copy other params 
		_otherParams = new HashMap<String, String>();
		_otherParams.putAll(otherParams);

		_baseDir = baseDir;
		
		// generate instructions (overwritten)
		createInstructions();
	}

	public String getBaseDir()
	{
		return _baseDir;
	}

	/**
	 * Method to be invoked to execute instructions for the external function
	 * invocation
	 * @throws DMLRuntimeException 
	 */
	@Override
	public void execute(ExecutionContext ec) throws DMLRuntimeException 
	{
		_runID = _idSeq.getNextID();
		
		// execute package function
		for( Instruction inst : _inst ) 
		{
			executeInstruction( (ExternalFunctionInvocationInstruction) inst );
		}

		// convert matrix metadata information for symbol table
		try 
		{
			for( DataIdentifier dat : getOutputParams() )
			{
				if( dat.getDataType()==DataType.MATRIX )
				{
					MatrixFormatMetaData md = (MatrixFormatMetaData) _variables.get(dat.getName()).getMetaData();
					MatrixFormatMetaData md2 = new MatrixFormatMetaData(md.getMatrixCharacteristics(),OutputInfo.BinaryBlockOutputInfo,InputInfo.BinaryBlockInputInfo);
					_variables.get(dat.getName()).setMetaData(md2);
				}
			}		
		} 
		catch (DMLRuntimeException ex) 
		{
			throw new PackageRuntimeException("Failed to change matrix metadata.", ex);
		}
		
	}
	
	/**
	 * Executes the external function instruction without the use of NIMBLE tasks.
	 * 
	 * @param inst
	 * @throws DMLRuntimeException 
	 * @throws NimbleCheckedRuntimeException
	 */
	@SuppressWarnings("unchecked")
	public void executeInstruction(ExternalFunctionInvocationInstruction inst) throws DMLRuntimeException 
	{
		String className = inst.getClassName();
		String configFile = inst.getConfigFile();

		if (className == null)
			throw new PackageRuntimeException("Class name can't be null");

		// create instance of package function.
		Object o;
		try 
		{
			Class<Instruction> cla = (Class<Instruction>) Class.forName(className);
			o = cla.newInstance();
			
			//set the base directory
			Method m = cla.getMethod(SET_BASE_DIR, String.class);
			if( m == null )
				throw new DMLRuntimeException("External functions of type CP must contain a method setBaseDir(string)");
			m.invoke(o, _baseDir);
		} 
		catch (Exception e) 
		{
			throw new PackageRuntimeException("Error generating package function object " + e.toString() );
		}

		if (!(o instanceof PackageFunction))
			throw new PackageRuntimeException("Class is not of type PackageFunction");

		PackageFunction func = (PackageFunction) o;

		// add inputs to this package function based on input parameter
		// and their mappings.
		setupInputs(func, inst.getInputParams(), this.getVariables());
		func.setConfiguration(configFile);

		//executes function
		func.execute();
		
		// verify output of function execution matches declaration
		// and add outputs to variableMapping and Metadata
		verifyAndAttachOutputs(func, inst.getOutputParams());
	}
	

	@Override
	protected void createInstructions() 
	{
		_inst = new ArrayList<Instruction>();

		// assemble information provided through keyvalue pairs
		String className = _otherParams.get(CLASSNAME);
		String configFile = _otherParams.get(CONFIGFILE);
		String execLocation = _otherParams.get(EXECLOCATION);

		// class name cannot be null, however, configFile and execLocation can
		// be null
		if (className == null)
			throw new PackageRuntimeException(CLASSNAME + " not provided!");

		// assemble input and output param strings
		String inputParameterString = getParameterString(getInputParams());
		String outputParameterString = getParameterString(getOutputParams());

		// generate instruction
		ExternalFunctionInvocationInstruction einst = new ExternalFunctionInvocationInstruction(
				className, configFile, execLocation, inputParameterString,
				outputParameterString);

		_inst.add(einst);

	}
}