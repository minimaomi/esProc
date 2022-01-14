package com.scudata.expression.fn;

import com.scudata.common.MessageManager;
import com.scudata.common.RQException;
import com.scudata.dm.ComputeStack;
import com.scudata.dm.Context;
import com.scudata.dm.DataStruct;
import com.scudata.dm.IComputeItem;
import com.scudata.dm.KeyWord;
import com.scudata.dm.LinkEntry;
import com.scudata.dm.Record;
import com.scudata.dm.Sequence;
import com.scudata.expression.Function;
import com.scudata.expression.IParam;
import com.scudata.expression.Move;
import com.scudata.expression.Node;
import com.scudata.resources.EngineMessage;

/**
 * ѭ�������е������㣬������ͬ�ֶ�ֵ�ĳ�Ա�ۻ�
 * get(level,F;a:b) �ڶ��ѭ��������ȡ���ϲ�Ļ���Ա��Ϣ������A.fn(B.fn(get(1))����ʾȡA�ĵ�ǰѭ����Ա
 * 					levelΪ�������Ĳ���������Ϊ0��
 * 					FΪ�ֶ�����#��ʾ��ţ�ʡ��ȡ��Ա��
 * 					a:b���������[��]��ͬ����ʡ�ԣ���ѭ���������޶���
 * @author runqian
 *
 */
public class Get extends Function {
	private int level = -1;
	private String fieldName; // ��Ҫȡ���ֶΣ����û��F������Ϊ��
	private boolean isSeq; // �Ƿ�ȡ��ǰѭ����ţ����fieldNameΪ�ղ���isSeqΪfalse��ȡ��ǰѭ����Ա
	private IParam moveParam;
	
	private DataStruct prevDs; // �ϴμ����Ӧ�����ݽṹ�����������Ż�
	private int col = -1; // �ϴμ����Ӧ���ֶ����������������Ż�
	
	public Node optimize(Context ctx) {
		if (param != null) param.optimize(ctx);
		return this;
	}
	
	private void prepare(IParam param, Context ctx) {
		if (param != null && param.getType() == IParam.Semicolon) {
			moveParam = param.getSub(1);
			param = param.getSub(0);
		}
		
		if (param == null) {
			level = 0;
		} else if (param.isLeaf()) {
			Object obj = param.getLeafExpression().calculate(ctx);
			if (obj instanceof Number) {
				level = ((Number)obj).intValue();
				if (level < 0) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("get" + mm.getMessage("function.missingParam"));
				}
			} else {
				MessageManager mm = EngineMessage.get();
				throw new RQException("get" + mm.getMessage("function.paramTypeError"));
			}
		} else if (param.getSubSize() == 2) {
			IParam levelParam = param.getSub(0);
			if (levelParam == null) {
				level = 0;
			} else {
				Object obj = levelParam.getLeafExpression().calculate(ctx);
				if (obj instanceof Number) {
					level = ((Number)obj).intValue();
					if (level < 0) {
						MessageManager mm = EngineMessage.get();
						throw new RQException("get" + mm.getMessage("function.missingParam"));
					}
				} else {
					MessageManager mm = EngineMessage.get();
					throw new RQException("get" + mm.getMessage("function.paramTypeError"));
				}
			}
			
			IParam fieldParam = param.getSub(1);
			if (fieldParam == null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("get" + mm.getMessage("function.invalidParam"));
			}
			
			fieldName = fieldParam.getLeafExpression().getIdentifierName();
			isSeq = fieldName.equals(KeyWord.CURRENTSEQ);
		} else {
			MessageManager mm = EngineMessage.get();
			throw new RQException("get" + mm.getMessage("function.invalidParam"));
		}
	}
	
	public Object calculate(Context ctx) {
		if (level == -1) {
			prepare(param, ctx);
		}
		
		ComputeStack stack = ctx.getComputeStack();
		LinkEntry<IComputeItem> entry = stack.getStackHeadEntry();
		if (entry == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("function.notInCyclicalFunction", "'get'"));
		}
		
		// ���ݲ�ȡ����Ӧ��ѭ������
		int level = this.level;
		while (level != 0) {
			level--;
			entry = entry.getNext();
			if (entry == null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(this.level + mm.getMessage("engine.indexOutofBound"));
			}
		}
		
		IComputeItem item = entry.getElement();
		if (moveParam == null) {
			// get(level,F)
			if (fieldName == null) {
				return item.getCurrent();
			} else if (isSeq) {
				return item.getCurrentIndex();
			} else {
				Object obj = item.getCurrent();
				return getFieldValue(obj);
			}
		} else {
			// get(level,F;a:b)
			if (!(item instanceof Sequence.Current)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("get" + mm.getMessage("function.invalidParam"));
			}
			
			Sequence.Current current = (Sequence.Current)item;
			if (moveParam.isLeaf()) {
				int pos = Move.calculateIndex(current, moveParam, ctx);
				if (fieldName == null) {
					return pos > 0 ? current.get(pos) : null;
				} else if (isSeq) {
					return pos;
				} else {
					if (pos < 1) {
						return null;
					}
					
					Object obj = current.get(pos);
					return getFieldValue(obj);
				}
			} else {
				int []range = Move.calculateIndexRange(current, moveParam, ctx);
				if (range == null) {
					return new Sequence(0);
				}
				
				if (fieldName == null) {
					int startSeq = range[0];
					int endSeq = range[1];
					Sequence result = new Sequence(endSeq - startSeq + 1);
					for (; startSeq <= endSeq; ++startSeq) {
						result.add(current.get(startSeq));
					}

					return result;
				} else if (isSeq) {
					return new Sequence(range[0], range[1]);
				} else {
					return Move.getFieldValues(current, fieldName, range[0], range[1]);
				}
			}
		}
	}
	
	// ȡobj��fieldName�ֶ�ֵ
	private Object getFieldValue(Object obj) {
		if (obj instanceof Record) {
			Record cur = (Record)obj;
			if (prevDs != cur.dataStruct()) {
				prevDs = cur.dataStruct();
				col = prevDs.getFieldIndex(fieldName);
				if (col < 0) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(fieldName + mm.getMessage("ds.fieldNotExist"));
				}
			}

			return cur.getNormalFieldValue(col);
		} else if (obj instanceof Sequence) {
			// �����ǰԪ����������ȡ���һ��Ԫ��
			if (((Sequence)obj).length() == 0) {
				return null;
			}
			
			obj = ((Sequence)obj).get(1);
			if (obj instanceof Record) {
				Record cur = (Record)obj;
				if (prevDs != cur.dataStruct()) {
					prevDs = cur.dataStruct();
					col = prevDs.getFieldIndex(fieldName);
					if (col < 0) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(fieldName + mm.getMessage("ds.fieldNotExist"));
					}
				}

				return cur.getNormalFieldValue(col);
			} else if (obj == null) {
				return null;
			} else {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("Expression.unknownExpression") + fieldName);
			}
		} else if (obj == null) {
			return null;
		} else {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("Expression.unknownExpression") + fieldName);
		}
	}
}