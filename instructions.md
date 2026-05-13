  | 模块 | 求解的方程/模型 | 可供其它 mod 采样的值 | 其它 mod 可实现的玩法 |
  |---|---|---|---|
  | Circuit Network | 基尔霍夫定律 + MNA：A x = b；元件关系 I=GV、I=C dV/dt、V=L dI/dt | 端口电压、电流、功率、等效电阻、储能、过载状态 | 电网、电池、发电机、负载、传感器、保险丝、控制电路、机器供电 |
  | Electrostatics | 泊松方程：div(epsilon grad(phi)) = -rho；E = -grad(phi) | 电势 phi、电场 E、电位差、电荷密度、能量密度、导体是否屏蔽 | 静电吸附、带电粒子偏转、电容、放电、法拉第笼、高压危险、静电传感器 |
  | Magnetostatics | 磁静态近似：curl H = J、div B = 0、B = mu H；实现上可用线圈/磁偶极子/Biot-Savart 近似 | 磁场 B、磁通量 Phi、磁场梯度、磁力、磁力矩、磁屏蔽系数 | 电磁铁、继电器、磁悬浮、磁力吸附、指南针、磁传感器、电磁制动 |
  | Induction / Coupling | 法拉第感应：emf = -dPhi/dt；运动导体：emf = integral((v x B) dl)；洛伦兹力：F = q(E + v x B)
  | 感应电压、反电动势、磁通变化率、电机扭矩、发电机输出、涡流损耗 | 电机、发电机、变压器、感应加热、感应刹车、电磁炮、Aeronautics 转轴联动 |
  | Signal / RF Layer | 不解 Maxwell，使用信号传播模型：距离衰减、遮挡、材料吸收、方向图、噪声、干扰、延迟 | 接收功率、SNR、频率、相位/延迟、频道占用、干扰强度、是否被屏蔽 | 无线控制、遥测、雷达、导航、干扰器、EMP、天线阵列、远程通信 |
  | Discharge / Arc Events | 击穿条件：|E| > E_break(material)；电弧路径用图搜索/最小代价路径；放电用 RC/RLC 暂态 | 电弧事件、放电电流、热量、击穿路径、EMP 脉冲强度 | 火花、高压电弧、雷击、特斯拉线圈、危险电网、设备烧毁 |
  | Experimental Maxwell Grid | 小范围 Yee/FDTD：dB/dt = -curl E，dD/dt = curl H - J，可使用缩放光速 | E(t)、B(t)、坡印廷矢量 S = E x H、波前、反射/驻波 | 教学可视化、波导、天线实验、微波腔、局部 debug；不作为默认玩法 |

  我会把默认 API 定义成三类：

  - 查询场：sampleElectricField、sampleMagneticField、samplePotential、sampleFlux
  - 查询电路：getVoltage、getCurrent、getPower、getStoredEnergy
  - 订阅事件：onArc、onOverload、onSignalReceived、onEMPPulse

  最重要的设计边界是：
  其它 mod 不应该依赖全场 Maxwell 网格。它们应该依赖稳定的端口、电场/磁场采样、信号传播和机电耦合 API。

  这样它就不是一个“电磁机器 mod”，而是一个真正能被 Aeronautics、机械、电力、武器、通信类 mod 使用的基础设施。