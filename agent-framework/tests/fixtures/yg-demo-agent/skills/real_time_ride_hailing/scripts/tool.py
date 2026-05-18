#!/usr/bin/env python3
"""打车服务技能 - 处理打车相关请求"""

import sys


def main(input_data: str = None) -> str:
    """打车服务技能入口函数
    
    Args:
        input_data: 用户输入的打车需求描述
        
    Returns:
        处理结果或引导话术
    """
    if not input_data:
        return "您好，请提供乘车人姓名、电话（如本人乘车，请回复本人）！"
    
    input_lower = input_data.lower()
    
    if "本人" in input_lower:
        return "已确认是客户本人打车，接下来请提供您的出发地和目的地哦～"
    
    if "出发地" in input_lower or "目的地" in input_lower:
        return "好的，正在为您查询可用车型及报价～"
    
    if "打车" in input_lower or "出行" in input_lower:
        return "您好，请提供乘车人姓名、电话（如本人乘车，请回复本人）！"
    
    return f"收到您的打车需求：{input_data}，请问是本人乘车还是帮人打车？"


if __name__ == "__main__":
    cmd = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else sys.stdin.read().strip()
    print(main(cmd))
